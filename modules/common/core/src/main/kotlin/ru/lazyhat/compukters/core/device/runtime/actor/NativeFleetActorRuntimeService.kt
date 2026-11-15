/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonLifecyclePort
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * Active-execution portion of the experimental native backend. Bootstrap transfers an already
 * started/restored host before registration; Rust owns its session from then until stop.
 * No JVM worker advances Guest instructions. Frame grants come from the capacity owner.
 *
 * Boot, checkpoint restoration, redstone/sound completion translation and backend selection are
 * not wired here yet. Addon completions are admitted with their continuation as an ordered batch.
 * Unsupported requests/effects are rejected before admission.
 * Addon requests and program lifecycle actions stream to a turn observer on the owner thread;
 * final-only callers and unsupported host/compilation events use [onExternalEvent]. Neither path
 * accumulates a second result backlog. Only the final Quiescent report settles turn credit.
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
    byteLimits: NativeFleetOutboxLimits = NativeFleetOutboxLimits(),
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
            byteLimits,
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
        require(effects.all { it is ProgramRuntimeActorEffect.RedstoneInput || it is ProgramRuntimeActorEffect.CompleteAddons }) {
            "native redstone/sound completion translation is not wired yet"
        }
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
        var commandCount = 1
        for (effect in effects) {
            val count = if (effect is ProgramRuntimeActorEffect.CompleteAddons) effect.completions.size else 1
            if (count > maximumRequestsPerActor - commandCount) return VmActorSubmission.MAILBOX_FULL
            commandCount += count
        }
        val commands = ArrayList<NativeFleetOutbox.Command>(commandCount)
        effects.forEach { effect ->
            when (effect) {
                is ProgramRuntimeActorEffect.RedstoneInput -> {
                    commands +=
                        NativeFleetOutbox.Command.RedstoneInput(RedstoneWire.requireInputPacket(effect.packet))
                }

                is ProgramRuntimeActorEffect.CompleteAddons -> {
                    effect.completions.forEach {
                        commands +=
                            NativeFleetOutbox.Command.Reply(it.identity, it.response)
                    }
                }

                else -> {
                    error("unsupported native effect")
                }
            }
        }
        val allowance = credit.remaining
        commands += NativeFleetOutbox.Command.Grant(worldTick, allowance.toInt(), credit.deadlineNanos)
        val id = requestId()
        if (!dispatcher.outbox.offer(actor.machine, commands)) return VmActorSubmission.MAILBOX_FULL
        effects.forEach { effect ->
            if (effect is ProgramRuntimeActorEffect.RedstoneInput) {
                actor.host.observeFleetRedstoneInput(
                    fleet,
                    actor.machine,
                    effect.packet,
                )
            }
        }
        actor.turn = Turn(id, allowance, completion) { machines[actor.machine] === actor && actor.stopping == null && actor.turn?.id == id }
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
        if (pendingQueries >= maximumRequests || (actor.queries?.size ?: 0) >= maximumRequestsPerActor) {
            return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL))
        }
        val outgoing =
            try {
                NativeFleetRequest.prepare(prepared)
                    ?: return CompletableFuture.failedFuture(
                        UnsupportedOperationException("native request is not wired: ${prepared.javaClass.simpleName}"),
                    )
            } catch (error: IllegalArgumentException) {
                return CompletableFuture.completedFuture(
                    ProgramRuntimeActorReply(
                        id,
                        actor.host.state,
                        ProgramRuntimeActorValue.Rejected(
                            ProgramRuntimeActorFailure.InvalidRequest(error.message ?: "invalid native request"),
                        ),
                    ),
                )
            }
        if ((prepared is ProgramRuntimeActorCommand.SendTerminalKey || prepared is ProgramRuntimeActorCommand.SendTerminalText) &&
            actor.host.state != ProgramRuntimeState.Running && actor.host.state != ProgramRuntimeState.WaitingForInput &&
            actor.host.state != ProgramRuntimeState.WaitingForCompiler
        ) {
            return CompletableFuture.completedFuture(
                ProgramRuntimeActorReply(id, actor.host.state, ProgramRuntimeActorValue.Accepted(false)),
            )
        }
        if (!dispatcher.outbox.offer(actor.machine, listOf(outgoing))) {
            return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL))
        }
        val result = CompletableFuture<ProgramRuntimeActorReply>()
        val queries = actor.queries ?: mutableMapOf<Long, Query>().also { actor.queries = it }
        queries[id.value] = Query(outgoing.response, result)
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
                observeOutcome(actor, event)
            }

            is ExperimentalFleetEvent.Quiescent -> {
                val actor = machines[event.machine] ?: return
                if (actor.stopping == null) finishTurn(actor, event)
            }

            is ExperimentalFleetEvent.FilesystemGeneration -> {
                val actor = machines[event.machine] ?: return
                finishQuery(actor, event.request, event)
            }

            is ExperimentalFleetEvent.Terminal -> {
                machines[event.machine]?.let {
                    finishQuery(it, event.request, event)
                }
            }

            is ExperimentalFleetEvent.InputResult -> {
                machines[event.machine]?.let { finishQuery(it, event.request, event) }
            }

            is ExperimentalFleetEvent.FileStat -> {
                machines[event.machine]?.let { finishQuery(it, event.request, event) }
            }

            is ExperimentalFleetEvent.FileList -> {
                machines[event.machine]?.let { finishQuery(it, event.request, event) }
            }

            is ExperimentalFleetEvent.FileRead -> {
                machines[event.machine]?.let { finishQuery(it, event.request, event) }
            }

            is ExperimentalFleetEvent.FileFailure -> {
                machines[event.machine]?.let { finishQuery(it, event.request, event) }
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

    private fun observeOutcome(
        actor: Actor,
        event: ExperimentalFleetEvent.Outcome,
    ) {
        val turn = actor.turn
        if (turn?.completion !is ProgramRuntimeTurnObserver) {
            actor.host.observeFleetOutcome(fleet, actor.machine, event.outcome)
            if (isCurrent(actor, turn)) onExternalEvent(actor.endpoint, event)
            return
        }
        actor.host.observeFleetOutcome(fleet, actor.machine, event.outcome, turn)
        if (!isCurrent(actor, turn)) return
        when (val outcome = event.outcome) {
            is VmOutcome.HostRequestBatch -> {
                var handled = false
                var unhandled: MutableList<VmHostRequest>? = null
                for (index in outcome.requests.indices) {
                    val request = outcome.requests[index]
                    if (!isCurrent(actor, turn)) return
                    val prepared = actor.host.prepareFleetAddonRequest(fleet, actor.machine, request)
                    if (prepared == null) {
                        if (handled) (unhandled ?: mutableListOf<VmHostRequest>().also { unhandled = it }).add(request)
                    } else {
                        if (!handled && index > 0) unhandled = ArrayList(outcome.requests.subList(0, index))
                        handled = true
                        turn.submit(ProgramAddonAction.Request(prepared))
                    }
                }
                if (!isCurrent(actor, turn)) return
                if (!handled) {
                    onExternalEvent(actor.endpoint, event)
                } else {
                    unhandled?.let { onExternalEvent(actor.endpoint, event.copy(outcome = VmOutcome.HostRequestBatch(it))) }
                }
            }

            is VmOutcome.ProcessEntered, is VmOutcome.ProcessExited, is VmOutcome.ProcessReplaced -> {
                Unit
            }

            else -> {
                onExternalEvent(actor.endpoint, event)
            }
        }
    }

    private fun isCurrent(
        actor: Actor,
        turn: Turn?,
    ): Boolean = machines[actor.machine] === actor && actor.stopping == null && actor.turn === turn

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
        val failure = turn.observationFailure
        complete(turn.completion, if (failure == null) reply else null, failure)
    }

    private fun finishQuery(
        actor: Actor,
        request: Long,
        event: ExperimentalFleetEvent,
    ) {
        val query = actor.queries?.remove(request) ?: return
        pendingQueries--
        if (actor.queries?.isEmpty() == true) actor.queries = null
        val value =
            try {
                query.response.decode(event).also { value ->
                    if (value is ProgramRuntimeActorValue.FileSystemGeneration) actor.generation = value.generation
                    if (value is ProgramRuntimeActorValue.Accepted && value.accepted) {
                        actor.host.observeFleetTerminalInput(
                            fleet,
                            actor.machine,
                            query.response == NativeFleetRequest.Response.INTERRUPT_INPUT,
                        )
                    }
                }
            } catch (error: Exception) {
                query.future.completeExceptionally(error)
                return
            }
        query.future.complete(ProgramRuntimeActorReply(ProgramRuntimeRequestId(request), actor.host.state, value, actor.generation))
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
                queries?.values?.forEach { it.future.completeExceptionally(failure) }
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
        var queries: MutableMap<Long, Query>? = null
        var stopping: CompletableFuture<Long>? = null
    }

    private class Turn(
        val id: ProgramRuntimeRequestId,
        val allowance: Long,
        val completion: ProgramRuntimeTurnCompletion,
        val isActive: () -> Boolean,
    ) : ProgramAddonLifecyclePort {
        var observationFailure: Exception? = null
            private set

        override fun submit(action: ProgramAddonAction): Boolean {
            if (observationFailure == null && isActive()) {
                try {
                    (completion as ProgramRuntimeTurnObserver).addonAction(action)
                } catch (failure: Exception) {
                    observationFailure = failure
                }
            }
            return true
        }
    }

    private class Query(
        val response: NativeFleetRequest.Response,
        val future: CompletableFuture<ProgramRuntimeActorReply>,
    )
}
