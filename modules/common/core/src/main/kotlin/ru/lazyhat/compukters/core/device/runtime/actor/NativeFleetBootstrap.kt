/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import java.util.concurrent.CompletableFuture

/** Temporary host ownership for boot/storage operations. Never advances Guest or owns an active fleet machine. */
internal class NativeFleetBootstrap(
    private val checkOwner: () -> Unit,
    private val onResultsReady: () -> Unit,
    private val reserve: (Long) -> AutoCloseable?,
    private val activate: (Entry, Long) -> Unit,
    private val onActorClosing: (VmActorEndpoint) -> Unit,
    private val nanoTime: () -> Long,
    private val prepareLeaseClose: (VmActorEndpoint, ProgramActorHibernation?) -> ProgramActorCloseReservation,
) {
    private val monitor = Object()
    private val work = ArrayDeque<Entry>()
    private val ready = ArrayDeque<Entry>()
    private val entries = mutableMapOf<VmActorEndpoint, Entry>()
    private val closing = mutableMapOf<VmActorEndpoint, CompletableFuture<Long?>>()
    private var worker: Thread? = null
    private var shutdown = false
    private var predecessors = 0
    private val stopped = CompletableFuture<Unit>()
    var lastTransferAttempts = 0
        private set
    var pendingRequests = 0
        private set

    val size: Int get() {
        closing.entries.removeAll { it.value.isDone }
        return entries.size + closing.size
    }
    val isEmpty: Boolean get() = entries.isEmpty()

    fun contains(endpoint: VmActorEndpoint): Boolean = endpoint in entries

    fun containsComputer(endpoint: VmActorEndpoint): Boolean =
        entries.keys.any { it.computerId == endpoint.computerId } || closing.keys.any { it.computerId == endpoint.computerId }

    fun attach(
        endpoint: VmActorEndpoint,
        host: ProgramRuntimeHost,
        lease: ProgramRuntimeActorLease? = null,
    ): ProgramRuntimeActorLease {
        checkOwner()
        val entry = Entry(endpoint, host, ProgramRuntimeActorProcessor(host))
        entry.lease = lease ?: ProgramRuntimeActorLease(endpoint) { request -> prepareLeaseClose(endpoint, request) }
        entries[endpoint] = entry
        return entry.lease
    }

    /** Reserve before fencing the native actor; neither commands nor close may touch its host before predecessor. */
    fun transition(
        endpoint: VmActorEndpoint,
        host: ProgramRuntimeHost,
        lease: ProgramRuntimeActorLease?,
        tick: Long,
        command: ProgramRuntimeActorCommand,
        predecessor: CompletableFuture<Long>,
    ): CompletableFuture<ProgramRuntimeActorReply>? {
        checkOwner()
        val reservation = reserve(0) ?: return null
        attach(endpoint, host, lease)
        val entry = entries.getValue(endpoint)
        entry.reservation = reservation
        entry.tick = tick
        synchronized(monitor) {
            entry.waiting = true
            predecessors++
            ensureWorker()
        }
        predecessor.whenComplete { _, failure ->
            synchronized(monitor) {
                predecessors--
                entry.predecessorFailure = failure
                entry.waiting = false
                if (entry.command != null || entry.closing) enqueue(entry)
            }
        }
        return request(endpoint, command)
    }

    fun request(
        endpoint: VmActorEndpoint,
        command: ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        val entry = entries.getValue(endpoint)
        if (command !is ProgramRuntimeActorCommand.Start && command !is ProgramRuntimeActorCommand.RestoreOrBoot &&
            command !is ProgramRuntimeActorCommand.CompleteRestoration &&
            command !is ProgramRuntimeActorCommand.AbortRestoration && command !is ProgramRuntimeActorCommand.StartBoot &&
            command !is ProgramRuntimeActorCommand.Shutdown && command !is ProgramRuntimeActorCommand.Reboot &&
            command !is ProgramRuntimeActorCommand.ResourceSnapshot && command !is ProgramRuntimeActorCommand.FileSystemGeneration &&
            command !is ProgramRuntimeActorCommand.TerminalFullState && command !is ProgramRuntimeActorCommand.TerminalChangesSince
        ) {
            return CompletableFuture.failedFuture(UnsupportedOperationException("native bootstrap does not support this command"))
        }
        if (entry.request != null) return refused()
        if (entry.reservation == null) {
            val bytes = if (command is ProgramRuntimeActorCommand.Start) 2L * command.artifactSize else 0L
            entry.reservation =
                try {
                    reserve(bytes) ?: return refused()
                } catch (failure: IllegalArgumentException) {
                    return CompletableFuture.failedFuture(failure)
                }
        }
        val result = CompletableFuture<ProgramRuntimeActorReply>()
        entry.request = result
        entry.tick =
            when (command) {
                is ProgramRuntimeActorCommand.RestoreOrBoot -> maxOf(entry.tick, command.worldTick)
                is ProgramRuntimeActorCommand.CompleteRestoration -> maxOf(entry.tick, command.worldTick)
                else -> entry.tick
            }
        pendingRequests++
        synchronized(monitor) {
            entry.command =
                when (command) {
                    is ProgramRuntimeActorCommand.AbortRestoration -> command.copy(detail = command.detail.take(1024))
                    is ProgramRuntimeActorCommand.RestoreOrBoot -> command.copy(worldTick = entry.tick)
                    is ProgramRuntimeActorCommand.CompleteRestoration -> command.copy(worldTick = entry.tick)
                    else -> command
                }
            if (!entry.waiting) enqueue(entry)
        }
        return result
    }

    /** Only ready bootstrap results are inspected; no scan of active or idle native machines. */
    fun pump(
        maximumEvents: Int,
        deadline: Long,
        maximumAttempts: Int = maximumEvents,
    ): Int {
        checkOwner()
        lastTransferAttempts = 0
        val allowance = synchronized(monitor) { minOf(maximumEvents, ready.size) }
        var delivered = 0
        repeat(allowance) {
            if (deadline != Long.MAX_VALUE && nanoTime() - deadline >= 0) return delivered
            val entry = synchronized(monitor) { ready.removeFirstOrNull() } ?: return delivered
            val outcome = checkNotNull(entry.outcome)
            val reply = outcome.getOrNull()
            if ((reply?.value as? ProgramRuntimeActorValue.Start)?.result == ProgramStartResult.Started) {
                if (lastTransferAttempts >= maximumAttempts) {
                    synchronized(monitor) { ready.addLast(entry) }
                    return@repeat
                }
                lastTransferAttempts++
                try {
                    activate(entry, entry.tick)
                } catch (_: VmFleetBusyException) {
                    synchronized(monitor) { ready.addLast(entry) }
                    return@repeat
                } catch (failure: Exception) {
                    failAndClose(entry, failure)
                    delivered++
                    return@repeat
                }
                entries.remove(entry.endpoint)
                entry.reservation?.close()
                entry.reservation = null
            }
            val result = checkNotNull(entry.request)
            entry.request = null
            entry.outcome = null
            pendingRequests--
            delivered++
            if (outcome.isFailure) {
                prepareClose(entry, null).commit()
                result.completeExceptionally(checkNotNull(outcome.exceptionOrNull()))
            } else {
                if (!entry.host.hasPreparedRestoration) {
                    entry.reservation?.close()
                    entry.reservation = null
                }
                result.complete(checkNotNull(reply))
            }
        }
        return delivered
    }

    private fun failAndClose(
        entry: Entry,
        failure: Exception,
    ) {
        val result = checkNotNull(entry.request)
        entry.request = null
        pendingRequests--
        prepareClose(entry, null).commit()
        result.completeExceptionally(failure)
    }

    fun stop(endpoint: VmActorEndpoint): CompletableFuture<Long?>? {
        checkOwner()
        closing[endpoint]?.let { return it.copy() }
        val entry = entries[endpoint] ?: return null
        return entry.lease.closeAsync()
    }

    fun prepareClose(
        endpoint: VmActorEndpoint,
        request: ProgramActorHibernation?,
    ): ProgramActorCloseReservation = prepareClose(entries.getValue(endpoint), request)

    private fun prepareClose(
        entry: Entry,
        request: ProgramActorHibernation?,
    ): ProgramActorCloseReservation {
        checkOwner()
        closing[entry.endpoint]?.let { result -> return ProgramActorCloseReservation { result.copy() } }
        check(entries[entry.endpoint] === entry)
        require(request == null || request.effects.isEmpty()) { "bootstrapping host cannot own world completions" }
        if (request != null && entry.reservation == null) {
            entry.reservation = reserve(0) ?: throw ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL)
        }
        return ProgramActorCloseReservation {
            // Fence and reserve worker ownership before any callback may reenter service shutdown.
            synchronized(monitor) {
                entry.closing = true
                entry.hibernation = request
                ready.remove(entry)
                if (!entry.running && !entry.queued && !entry.waiting) enqueue(entry)
            }
            entries.remove(entry.endpoint)
            closing[entry.endpoint] = entry.closed
            val pending = entry.request
            entry.request = null
            if (pending != null) pendingRequests--
            try {
                runCatching { onActorClosing(entry.endpoint) }.exceptionOrNull()?.let {
                    LOGGER.warn(it) { "Bootstrap owner cleanup failed" }
                }
                pending?.completeExceptionally(ProgramRuntimeActorRequestException(VmActorSubmission.CLOSED))
            } finally {
                synchronized(monitor) {
                    entry.closeEnabled = true
                    monitor.notifyAll()
                }
            }
            entry.closed.copy()
        }
    }

    fun closeAsync(): CompletableFuture<Unit> =
        synchronized(monitor) {
            check(entries.isEmpty()) { "bootstrap actors must be fenced before shutdown" }
            shutdown = true
            if (worker == null) stopped.complete(Unit)
            monitor.notifyAll()
            stopped.copy()
        }

    private fun enqueue(entry: Entry) {
        check(!entry.queued)
        entry.queued = true
        work.addLast(entry)
        ensureWorker()
        monitor.notifyAll()
    }

    /** Caller holds monitor. The worker also waits for outstanding native ownership barriers at shutdown. */
    private fun ensureWorker() {
        if (worker == null) {
            worker =
                Thread
                    .ofPlatform()
                    .daemon(true)
                    .name("compukters-native-bootstrap")
                    .start(::run)
        }
    }

    private fun run() {
        try {
            while (runNext()) { /* A completed task must leave the worker stack before idle waiting. */ }
        } catch (failure: Throwable) {
            stopped.completeExceptionally(failure)
        } finally {
            stopped.complete(Unit)
        }
    }

    private fun runNext(): Boolean {
        val (entry, closeTask) =
            synchronized(monitor) {
                var next = work.firstOrNull { !it.closing || it.closeEnabled }
                while (next == null) {
                    if (shutdown && work.isEmpty() && predecessors == 0) return false
                    monitor.wait()
                    next = work.firstOrNull { !it.closing || it.closeEnabled }
                }
                check(work.remove(next))
                next.queued = false
                next.running = true
                next to next.closing
            }
        if (closeTask) {
            val outcome =
                runCatching {
                    // A prepared restore has never resumed: keep its durable checkpoint on unload.
                    try {
                        entry.hibernation?.let { request ->
                            if (entry.host.hasPreparedRestoration) {
                                if (!request.saveExecution) entry.host.discardCheckpoint()
                            } else {
                                entry.processor.prepareHibernation(request)
                            }
                        }
                    } finally {
                        entry.processor.close()
                    }
                    entry.processor.closed.join()
                }
            entry.reservation?.close()
            entry.reservation = null
            entry.outcome = null
            outcome.fold(entry.closed::complete, entry.closed::completeExceptionally)
        } else {
            val result =
                runCatching {
                    entry.predecessorFailure?.let { throw it }
                    entry.processor.process(checkNotNull(entry.command))
                }
            val publish =
                synchronized(monitor) {
                    entry.running = false
                    entry.command = null
                    if (entry.closing) {
                        enqueue(entry)
                        false
                    } else {
                        entry.outcome = result
                        ready.addLast(entry)
                        true
                    }
                }
            if (publish) runCatching(onResultsReady).exceptionOrNull()?.let { LOGGER.warn(it) { "Bootstrap wakeup failed" } }
        }
        return true
    }

    private fun refused(): CompletableFuture<ProgramRuntimeActorReply> =
        CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL))

    class Entry(
        val endpoint: VmActorEndpoint,
        val host: ProgramRuntimeHost,
        val processor: ProgramRuntimeActorProcessor,
    ) {
        val closed = CompletableFuture<Long?>()
        lateinit var lease: ProgramRuntimeActorLease
        var request: CompletableFuture<ProgramRuntimeActorReply>? = null
        var reservation: AutoCloseable? = null
        var tick = 0L
        var outcome: Result<ProgramRuntimeActorReply>? = null
        var command: ProgramRuntimeActorCommand? = null
        var waiting = false
        var predecessorFailure: Throwable? = null
        var queued = false
        var running = false
        var closing = false
        var closeEnabled = false
        var hibernation: ProgramActorHibernation? = null
    }
}
