/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

/**
 * One lazy control worker per fleet, never a worker per machine. It owns closing hosts and
 * their unsent command batches exclusively. Ordinary output and world callbacks stay on the owner.
 * Only the final generation escapes; canceled request payloads are not queued back to the JVM owner.
 */
internal class NativeFleetCloser(
    private val fleet: ExperimentalFleet,
) {
    private val monitor = Object()
    private val pending = ArrayDeque<Close>()
    private var worker: Thread? = null
    private var closing = false
    private val stopped = CompletableFuture<Unit>()

    fun checkHealthy() {
        check(!stopped.isCompletedExceptionally) { "native lifecycle worker failed" }
    }

    fun reserve(
        machine: Long,
        host: ProgramRuntimeHost,
        commands: NativeFleetOutbox.Handoff,
        result: CompletableFuture<Long>,
    ): () -> Unit {
        val task = Close(machine, host, commands, result)
        synchronized(monitor) {
            checkHealthy()
            check(!closing)
            pending.addLast(task)
            if (worker == null) {
                worker =
                    Thread
                        .ofPlatform()
                        .daemon(true)
                        .name("compukters-native-fleet-close")
                        .start(::run)
            }
            monitor.notifyAll()
        }
        return {
            synchronized(monitor) {
                task.ready = true
                monitor.notifyAll()
            }
        }
    }

    fun closeAsync(): CompletableFuture<Unit> =
        synchronized(monitor) {
            closing = true
            if (worker == null) stopped.complete(Unit)
            monitor.notifyAll()
            stopped.copy()
        }

    private fun run() {
        var blocked = 0
        var active: Close? = null
        try {
            while (true) {
                val task =
                    synchronized(monitor) {
                        while (pending.isEmpty() && !closing) monitor.wait()
                        if (pending.isEmpty()) return
                        pending.removeFirst()
                    }
                active = task
                val progress = progress(task)
                active = null
                synchronized(monitor) {
                    if (progress != Progress.DONE) pending.addLast(task)
                    blocked = if (progress == Progress.BLOCKED) blocked + 1 else 0
                    // Back off only after an entire blocked round, never after accepted work.
                    if (pending.isNotEmpty() && blocked >= pending.size) {
                        monitor.wait(2)
                        blocked = 0
                    }
                }
            }
        } catch (failure: Throwable) {
            synchronized(monitor) {
                active?.let { pending.addFirst(it) }
                pending.forEach { it.result.completeExceptionally(failure) }
                stopped.completeExceptionally(failure)
            }
        } finally {
            stopped.complete(Unit)
        }
    }

    private fun progress(task: Close): Progress {
        if (!task.ready) return Progress.BLOCKED
        val event =
            try {
                if (task.commands.sendNext(fleet, task.machine)) return Progress.MORE
                if (!task.submitted) {
                    fleet.stop(task.machine, independent = true)
                    task.submitted = true
                    return Progress.MORE
                }
                fleet.pollLifecycle(task.machine) ?: return Progress.BLOCKED
            } catch (_: VmFleetBusyException) {
                return Progress.BLOCKED
            } catch (failure: Exception) {
                // An unaccepted head remains owned and charged. Do not silently drop acknowledged
                // effects or claim a close barrier while the machine is still native-owned.
                if (!task.reportedFailure) {
                    task.reportedFailure = true
                    LOGGER.warn(failure) { "Native close transport failed; retaining and retrying accepted work" }
                }
                return Progress.BLOCKED
            }
        if (event is ExperimentalFleetEvent.Quiescent) return Progress.MORE
        try {
            check(event is ExperimentalFleetEvent.Stopped && event.machine == task.machine) { "unexpected native stop output: $event" }
            task.host.acknowledgeFleetStop(fleet, event, ProgramRuntimeState.Closed)
            task.result.complete(event.filesystemGeneration)
        } catch (failure: Throwable) {
            task.result.completeExceptionally(failure)
        }
        return Progress.DONE
    }

    private enum class Progress { MORE, BLOCKED, DONE }

    private class Close(
        val machine: Long,
        val host: ProgramRuntimeHost,
        val commands: NativeFleetOutbox.Handoff,
        val result: CompletableFuture<Long>,
    ) {
        @Volatile var ready = false
        var submitted = false
        var reportedFailure = false
    }
}
