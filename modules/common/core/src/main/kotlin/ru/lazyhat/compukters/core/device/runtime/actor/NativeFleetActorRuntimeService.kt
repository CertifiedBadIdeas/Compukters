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
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * Active-execution portion of the experimental native backend. Bootstrap transfers an already
 * started/restored host before registration; Rust owns its session from then until stop.
 * No JVM worker advances Guest instructions. Frame grants come from the capacity owner.
 *
 * Boot, checkpoint restoration, world-effect completion translation and backend selection are
 * not wired here yet. Unsupported requests/effects are rejected before admission. External
 * host/compilation events go to [onExternalEvent] on the owner, without a second result backlog.
 * Stop is asynchronous but currently requires this service's owner pump; it is not yet a
 * replacement for the production lease's independent shutdown barrier.
 * The caller owns the fleet and closes this service only after all stops have completed.
 */
internal class NativeFleetActorRuntimeService(
    private val fleet: ExperimentalFleet,
    private val maximumActors: Int,
    private val maximumRequests: Int,
    private val maximumRequestsPerActor: Int,
    private val checkOwner: () -> Unit,
    onResultsReady: () -> Unit,
    private val onExternalEvent: (VmActorEndpoint, ExperimentalFleetEvent) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) : ActorRuntimeService,
    AutoCloseable {
    init {
        require(maximumActors > 0 && maximumRequests > 0 && maximumRequestsPerActor > 0)
        checkOwner()
        require(fleet.coordinatedTurns) { "native actor service requires a coordinated fleet" }
    }

    override val redstoneInputCapacity: Int get() = maximumRequestsPerActor
    private val actors = mutableMapOf<VmActorEndpoint, Actor>()
    private val machines = mutableMapOf<Long, Actor>()
    private var closed = false
    private var nextRequest = 0L
    private var pendingQueries = 0
    internal var hostContinuationSamples = 0L
        private set
    internal var totalHostContinuationDelayTicks = 0L
        private set
    internal var maximumHostContinuationDelayTicks = 0L
        private set
    internal var coalescedRedstoneInputs = 0L
        private set
    private val dispatcher =
        NativeFleetDispatcher(
            fleet,
            maximumRequests,
            maximumRequestsPerActor,
            checkOwner,
            onResultsReady,
            ::observe,
        )

    /** The caller retains ownership if admission fails; successful registration owns host metadata. */
    fun register(
        endpoint: VmActorEndpoint,
        machine: Long,
        host: ProgramRuntimeHost,
    ): Boolean {
        checkOwner()
        check(!closed) { "native actor service is closed" }
        host.requireFleetOwnership(fleet, machine)
        if (actors.size >= maximumActors || machine in machines || actors.keys.any { it.computerId == endpoint.computerId }) return false
        val actor = Actor(endpoint, machine, host)
        actors[endpoint] = actor
        machines[machine] = actor
        return true
    }

    /** A frame can be installed once; a still-in-flight reservation cannot be borrowed or replaced. */
    fun grantFrame(
        endpoint: VmActorEndpoint,
        tick: Long,
        retirements: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Boolean {
        checkOwner()
        require(tick >= 0 && retirements >= 0)
        if (closed) return false
        val actor = actors[endpoint] ?: return false
        val previous = actor.credit
        if (actor.stopping != null || (previous != null && (previous.inFlight || tick <= previous.worldTick))) return false
        actor.credit = ProgramRuntimeFrameCredit(tick, retirements.toLong(), deadlineNanos, false)
        return true
    }

    override fun submitTurn(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect>,
        completion: ProgramRuntimeTurnCompletion,
    ): VmActorSubmission = admit(endpoint, worldTick, effects, completion, continuation = false)

    override fun continueTurn(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect>,
        completion: ProgramRuntimeTurnCompletion,
    ): Boolean = admit(endpoint, worldTick, effects, completion, continuation = true) == VmActorSubmission.ACCEPTED

    private fun admit(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect>,
        completion: ProgramRuntimeTurnCompletion,
        continuation: Boolean,
    ): VmActorSubmission {
        checkOwner()
        require(worldTick >= 0)
        if (closed) return VmActorSubmission.CLOSED
        require(effects.all { it is ProgramRuntimeActorEffect.RedstoneInput }) { "native world completion translation is not wired yet" }
        val actor = actors[endpoint] ?: return VmActorSubmission.STALE_ENDPOINT
        if (actor.stopping != null) return VmActorSubmission.CLOSED
        val credit = actor.credit ?: return VmActorSubmission.MAILBOX_FULL
        if (actor.turn != null || credit.inFlight) return VmActorSubmission.PERMIT_PENDING
        if (credit.worldTick != worldTick || (credit.deadlineNanos != Long.MAX_VALUE && nanoTime() - credit.deadlineNanos >= 0)) {
            return VmActorSubmission.MAILBOX_FULL
        }
        if (continuation) {
            if (actor.lastTurnTick != worldTick || credit.continuations >= MAXIMUM_CONTINUATIONS_PER_FRAME ||
                credit.remaining == 0L
            ) {
                return VmActorSubmission.MAILBOX_FULL
            }
        } else if (actor.lastTurnTick == worldTick) {
            return VmActorSubmission.PERMIT_PENDING
        }
        if (effects.size >= maximumRequestsPerActor) return VmActorSubmission.MAILBOX_FULL
        val commands = ArrayList<NativeFleetOutbox.Command>(effects.size + 1)
        effects.forEach {
            val packet = RedstoneWire.requireInputPacket((it as ProgramRuntimeActorEffect.RedstoneInput).packet)
            commands += NativeFleetOutbox.Command.RedstoneInput(packet)
        }
        val allowance = credit.remaining
        commands += NativeFleetOutbox.Command.Grant(worldTick, allowance.toInt(), credit.deadlineNanos)
        val id = requestId()
        if (!dispatcher.outbox.offer(actor.machine, commands)) return VmActorSubmission.MAILBOX_FULL
        effects.forEach {
            actor.host.observeFleetRedstoneInput(
                fleet,
                actor.machine,
                (it as ProgramRuntimeActorEffect.RedstoneInput).packet,
            )
        }
        actor.turn = Turn(id, allowance, completion)
        credit.inFlight = true
        if (continuation) credit.continuations++
        actor.lastTurnTick = worldTick
        return VmActorSubmission.ACCEPTED
    }

    override fun request(
        endpoint: VmActorEndpoint,
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        if (closed) return CompletableFuture.failedFuture(ProgramRuntimeActorServiceClosedException())
        val id = requestId()
        val prepared = command(id)
        require(prepared.requestId == id) { "runtime command factory returned a mismatched request id" }
        val actor =
            actors[endpoint]
                ?: return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.STALE_ENDPOINT))
        if (actor.stopping != null) return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.CLOSED))
        val outgoing =
            when (prepared) {
                is ProgramRuntimeActorCommand.FileSystemGeneration -> NativeFleetOutbox.Command.FilesystemGeneration(id.value)

                is ProgramRuntimeActorCommand.TerminalChangesSince -> NativeFleetOutbox.Command.TerminalUpdate(id.value, prepared.revision)

                else -> return CompletableFuture.failedFuture(
                    UnsupportedOperationException("native request is not wired: ${prepared.javaClass.simpleName}"),
                )
            }
        if (pendingQueries >= maximumRequests || (actor.queries?.size ?: 0) >= maximumRequestsPerActor ||
            !dispatcher.outbox.offer(actor.machine, listOf(outgoing))
        ) {
            return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL))
        }
        val result = CompletableFuture<ProgramRuntimeActorReply>()
        val queries = actor.queries ?: mutableMapOf<Long, CompletableFuture<ProgramRuntimeActorReply>>().also { actor.queries = it }
        queries[id.value] = result
        pendingQueries++
        // Cancellation does not release admission until the native response arrives. Otherwise
        // canceled futures could bypass the bound while their commands/responses are still retained.
        return result
    }

    fun stop(endpoint: VmActorEndpoint): CompletableFuture<Long>? {
        checkOwner()
        val actor = actors[endpoint] ?: return null
        actor.stopping?.let { return it.copy() }
        if (!dispatcher.outbox.offer(actor.machine, listOf(NativeFleetOutbox.Command.Stop))) return null
        return CompletableFuture<Long>().also { actor.stopping = it }.copy()
    }

    /** Both channels share this time/event allowance; control is serviced before ordinary output. */
    fun pump(
        maximumEvents: Int,
        maximumAttempts: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Int {
        checkOwner()
        require(maximumEvents >= 0 && maximumAttempts >= 0)
        val control = dispatcher.pumpLifecycle(maximumEvents, maximumAttempts, deadlineNanos, ::observeStop)
        return control + dispatcher.pump(maximumEvents - control, maximumAttempts, deadlineNanos)
    }

    private fun observe(event: ExperimentalFleetEvent) {
        when (event) {
            is ExperimentalFleetEvent.Outcome -> {
                val actor = machines[event.machine] ?: return
                if (actor.stopping != null) return
                actor.host.observeFleetOutcome(fleet, actor.machine, event.outcome)
                onExternalEvent(actor.endpoint, event)
            }

            is ExperimentalFleetEvent.Quiescent -> {
                val actor = machines[event.machine] ?: return
                if (actor.stopping == null) finishTurn(actor, event)
            }

            is ExperimentalFleetEvent.FilesystemGeneration -> {
                val actor = machines[event.machine] ?: return
                actor.generation = event.generation
                finishQuery(actor, event.request, ProgramRuntimeActorValue.FileSystemGeneration(event.generation))
            }

            is ExperimentalFleetEvent.Terminal -> {
                machines[event.machine]?.let {
                    finishQuery(it, event.request, ProgramRuntimeActorValue.TerminalUpdateValue(event.update))
                }
            }

            // The integration owner settles these typed events. It must not execute Guest code.
            is ExperimentalFleetEvent.Inspection -> {
                val actor = machines[event.machine] ?: return
                if (actor.stopping == null) onExternalEvent(actor.endpoint, event)
            }

            is ExperimentalFleetEvent.Compilation -> {
                val actor = machines[event.machine] ?: return
                if (actor.stopping != null) return
                actor.host.observeFleetOutcome(fleet, actor.machine, VmOutcome.CompilationRequested(event.request))
                onExternalEvent(actor.endpoint, event)
            }

            is ExperimentalFleetEvent.ScopeStopped -> {
                machines[event.machine]?.let { onExternalEvent(it.endpoint, event) }
            }

            is ExperimentalFleetEvent.Error -> {
                val machine = event.machine
                if (machine != null) {
                    val actor = machines[machine] ?: return
                    if (actor.stopping != null) return
                }
                error("native fleet failed: ${event.message}")
            }

            else -> {
                error("unexpected event in active native backend: $event")
            }
        }
    }

    private fun finishTurn(
        actor: Actor,
        event: ExperimentalFleetEvent.Quiescent,
    ) {
        val turn = actor.turn ?: return // Duplicate final reports cannot mint credit or another reply.
        val credit = checkNotNull(actor.credit)
        check(event.tick == credit.worldTick) { "native turn report belongs to another frame" }
        actor.host.observeFleetQuiescent(fleet, event)
        val delta = credit.finishCumulative(event.retired, turn.allowance)
        actor.turn = null
        val reply = ProgramRuntimeActorReply(turn.id, actor.host.state, ProgramRuntimeActorValue.None, actor.generation, delta)
        complete(turn.completion, reply, null)
    }

    private fun finishQuery(
        actor: Actor,
        request: Long,
        value: ProgramRuntimeActorValue,
    ) {
        val future = actor.queries?.remove(request) ?: return
        pendingQueries--
        if (actor.queries?.isEmpty() == true) actor.queries = null
        future.complete(ProgramRuntimeActorReply(ProgramRuntimeRequestId(request), actor.host.state, value, actor.generation))
    }

    private fun observeStop(event: ExperimentalFleetEvent) {
        when (event) {
            is ExperimentalFleetEvent.Quiescent -> {
                // Stop cancels the outstanding turn; its report cannot create another grant.
            }

            is ExperimentalFleetEvent.Stopped -> {
                val actor = checkNotNull(machines.remove(event.machine))
                val cancellationFailure = runCatching { onExternalEvent(actor.endpoint, event) }.exceptionOrNull()
                val hostCloseFailure =
                    runCatching {
                        actor.host.acknowledgeFleetStop(
                            fleet,
                            event,
                            ProgramRuntimeState.Closed,
                        )
                    }.exceptionOrNull()
                val closeFailure =
                    cancellationFailure?.also { if (hostCloseFailure != null) it.addSuppressed(hostCloseFailure) } ?: hostCloseFailure
                actors.remove(actor.endpoint)
                val failure = closeFailure ?: CancellationException("native actor stopped")
                actor.turn?.let { complete(it.completion, null, failure) }
                actor.turn = null
                val queries = actor.queries
                actor.queries = null
                pendingQueries -= queries?.size ?: 0
                queries?.values?.forEach { it.completeExceptionally(failure) }
                val stopped = checkNotNull(actor.stopping)
                if (closeFailure == null) stopped.complete(event.filesystemGeneration) else stopped.completeExceptionally(closeFailure)
                if (closeFailure is Error) throw closeFailure
            }

            else -> {
                error("unexpected native stop output: $event")
            }
        }
    }

    private fun requestId(): ProgramRuntimeRequestId = ProgramRuntimeRequestId(Math.incrementExact(nextRequest).also { nextRequest = it })

    override fun recordHostContinuationDelay(delayTicks: Long) {
        checkOwner()
        require(delayTicks >= 0)
        if (hostContinuationSamples < Long.MAX_VALUE) hostContinuationSamples++
        totalHostContinuationDelayTicks += delayTicks.coerceAtMost(Long.MAX_VALUE - totalHostContinuationDelayTicks)
        maximumHostContinuationDelayTicks = maxOf(maximumHostContinuationDelayTicks, delayTicks)
    }

    override fun recordCoalescedRedstoneInput() {
        checkOwner()
        if (coalescedRedstoneInputs < Long.MAX_VALUE) coalescedRedstoneInputs++
    }

    fun closeAsync(): CompletableFuture<Unit> {
        checkOwner()
        check(actors.isEmpty()) { "native actor stops must complete before closing dispatch" }
        return dispatcher.closeAsync().also { closed = true }
    }

    override fun close() {
        closeAsync().join()
    }

    private fun complete(
        completion: ProgramRuntimeTurnCompletion,
        reply: ProgramRuntimeActorReply?,
        failure: Throwable?,
    ) {
        try {
            completion.complete(reply, failure)
        } catch (
            error: Exception,
        ) {
            LOGGER.warn(error) { "Native actor turn completion failed" }
        }
    }

    private class Actor(
        val endpoint: VmActorEndpoint,
        val machine: Long,
        val host: ProgramRuntimeHost,
    ) {
        var credit: ProgramRuntimeFrameCredit? = null
        var lastTurnTick = -1L
        var turn: Turn? = null
        var generation: Long? = null
        var queries: MutableMap<Long, CompletableFuture<ProgramRuntimeActorReply>>? = null
        var stopping: CompletableFuture<Long>? = null
    }

    private class Turn(
        val id: ProgramRuntimeRequestId,
        val allowance: Long,
        val completion: ProgramRuntimeTurnCompletion,
    )
}
