/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.runtime.program.HostRequestRoutes
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonLifecyclePort
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldRequest
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldResult
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
 * Boot, checkpoint restoration and backend selection are not wired here yet. The carrier streams
 * addon replies, redstone confirmations and world replies into one bounded ordered outbox,
 * independently of the next execution grant. Legacy deferred world effects reject before admission.
 * Addon/world requests and program lifecycle actions stream to a turn observer on the owner thread;
 * final-only callers and unsupported host/compilation events use [onExternalEvent]. Neither path
 * accumulates a second result backlog. Only the final Quiescent report settles turn credit.
 * Stop fences owner work and transfers the host and charged unsent commands to one shared
 * lifecycle worker. Its native cleanup barrier completes without owner result pumping.
 * The caller owns the fleet. Service shutdown requires all actors fenced for stop and waits
 * for host cleanup and both shared threads before the caller may close the fleet.
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
    private val onActorClosing: (VmActorEndpoint) -> Unit,
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
    private val closing = mutableMapOf<VmActorEndpoint, CompletableFuture<Long>>()
    private val closer = NativeFleetCloser(fleet)
    private var observationDepth = 0
    private var deferredCloseStarts: MutableList<() -> Unit>? = null
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
        closer.checkHealthy()
        closing.entries.removeAll { it.value.isDone }
        if (actors.size + closing.size >= maximumActors || machine in machines ||
            actors.keys.any {
                it.computerId == endpoint.computerId
            } ||
            closing.keys.any { it.computerId == endpoint.computerId }
        ) {
            return false
        }
        val actor = Actor(endpoint, machine, host)
        actors[endpoint] = actor
        machines[machine] = actor
        return true
    }

    /** Lease for an already started/restored and transferred host; bootstrap stays outside this service. */
    fun attach(
        endpoint: VmActorEndpoint,
        machine: Long,
        host: ProgramRuntimeHost,
    ): ProgramRuntimeActorLease? {
        if (!register(endpoint, machine, host)) return null
        return ProgramRuntimeActorLease(endpoint) { request ->
            prepareLeaseClose(endpoint, request)
        }.also { actors.getValue(endpoint).lease = it }
    }

    private fun prepareLeaseClose(
        endpoint: VmActorEndpoint,
        request: ProgramActorHibernation?,
    ): ProgramActorCloseReservation {
        checkOwner()
        closer.checkHealthy()
        closing[endpoint]?.let { result -> return ProgramActorCloseReservation { result.thenApply { it } } }
        val actor = actors[endpoint] ?: throw ProgramRuntimeActorRequestException(VmActorSubmission.STALE_ENDPOINT)
        if (request == null) return ProgramActorCloseReservation { checkNotNull(stop(endpoint)).thenApply { it } }
        return prepareCheckpoint(actor, request.worldTick, request.addonState, request.effects, request.saveExecution)
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
        if (previous != null && (previous.inFlight || tick <= previous.worldTick)) return false
        actor.credit = ProgramRuntimeFrameCredit(tick, retirements.toLong(), deadlineNanos, false)
        return true
    }

    override fun submitHostCompletions(
        endpoint: VmActorEndpoint,
        completions: List<ProgramRuntimeHostCompletion>,
    ): Int {
        checkOwner()
        if (closed || endpoint in closing) return 0
        val actor = actors[endpoint] ?: return 0
        return dispatcher.outbox.offerPrefix(
            actor.machine,
            completions.map {
                when (it) {
                    is ProgramRuntimeHostCompletion.Reply -> NativeFleetOutbox.Command.Reply(it.identity, it.response)
                    is ProgramRuntimeHostCompletion.ConfirmRedstoneOutput -> NativeFleetOutbox.Command.ConfirmRedstoneOutput(it.packed)
                }
            },
        )
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
            "native world completions require the streamed host completion path"
        }
        if (endpoint in closing) return VmActorSubmission.CLOSED
        val actor = actors[endpoint] ?: return VmActorSubmission.STALE_ENDPOINT
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
        actor.turn = Turn(id, allowance, completion) { machines[actor.machine] === actor && actor.turn?.id == id }
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
        if (endpoint in closing) return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.CLOSED))
        val actor =
            actors[endpoint]
                ?: return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.STALE_ENDPOINT))
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

    /**
     * Fence and cancel owner work before handing the host to the shared close worker. External
     * resources are canceled by endpoint, not by retaining the native list of pending identities.
     * The returned barrier needs no owner pumping and cannot be canceled by canceling its copy.
     */
    fun stop(endpoint: VmActorEndpoint): CompletableFuture<Long>? = finish(endpoint, null, null)

    /** Accepted effects must already be queued. Refusal leaves this actor running and retryable. */
    fun hibernate(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        addonState: ByteArray,
    ): CompletableFuture<Long>? {
        checkOwner()
        closer.checkHealthy()
        closing[endpoint]?.let { return it.copy() }
        val actor = actors[endpoint] ?: return null
        return try {
            prepareCheckpoint(actor, worldTick, addonState, emptyList(), true).commit().thenApply { checkNotNull(it) }
        } catch (failure: ProgramRuntimeActorRequestException) {
            CompletableFuture.failedFuture(failure)
        }
    }

    private fun prepareCheckpoint(
        actor: Actor,
        worldTick: Long,
        addonState: ByteArray,
        effects: List<ProgramRuntimeActorEffect>,
        saveExecution: Boolean,
    ): ProgramActorCloseReservation {
        actor.host.validateFleetCheckpointTick(worldTick)
        require(worldTick >= (actor.credit?.worldTick ?: 0)) { "checkpoint tick precedes frame credit" }
        require(
            effects.all {
                it is ProgramRuntimeActorEffect.CompleteAddons
            },
        ) { "native final world completions require the streamed host completion path" }
        var count = 0
        for (effect in effects) {
            val completions = (effect as ProgramRuntimeActorEffect.CompleteAddons).completions
            if (completions.size > maximumRequestsPerActor - count) {
                throw ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL)
            }
            count += completions.size
        }
        val commands = ArrayList<NativeFleetOutbox.Command>(count)
        effects.forEach { effect ->
            (effect as ProgramRuntimeActorEffect.CompleteAddons).completions.forEach {
                commands += NativeFleetOutbox.Command.Reply(it.identity, it.response)
            }
        }
        val payload =
            dispatcher.outbox.reserveCheckpointPayload(
                actor.machine,
                if (saveExecution) addonState else byteArrayOf(),
                saveExecution,
                commands,
            ) ?: throw ProgramActorCloseDeferredException()
        try {
            if (!dispatcher.outbox.offer(actor.machine, commands)) {
                throw ProgramActorCloseDeferredException()
            }
        } catch (failure: Throwable) {
            payload.close()
            throw failure
        }
        // Nothing pumps this owner queue between reservation and commit. The carrier can fence
        // callbacks before its accepted completions and checkpoint ownership move to the worker.
        return ProgramActorCloseReservation {
            checkNotNull(finish(actor.endpoint, worldTick, payload, saveExecution)).thenApply { it }
        }
    }

    private fun finish(
        endpoint: VmActorEndpoint,
        checkpointTick: Long?,
        payload: NativeFleetOutbox.CheckpointPayload?,
        saveExecution: Boolean = true,
    ): CompletableFuture<Long>? {
        checkOwner()
        closer.checkHealthy()
        closing[endpoint]?.let {
            payload?.close()
            return it.copy()
        }
        val actor =
            actors.remove(endpoint) ?: run {
                payload?.close()
                return null
            }
        machines.remove(actor.machine)
        val result = CompletableFuture<Long>()
        closing[endpoint] = result
        val commands = dispatcher.outbox.handoffForClose(actor.machine)
        var cancellationFailure: Throwable? = null
        val native = CompletableFuture<Long>()
        native.whenComplete { generation, closeFailure ->
            val canceled = cancellationFailure
            if (canceled != null) {
                if (closeFailure != null) canceled.addSuppressed(closeFailure)
                result.completeExceptionally(canceled)
            } else if (closeFailure != null) {
                result.completeExceptionally(closeFailure)
            } else {
                result.complete(generation)
            }
        }
        // Reserve before invoking callbacks: a reentrant service close must include this host.
        val start = closer.reserve(actor.machine, actor.host, commands, native, checkpointTick, payload, saveExecution)
        try {
            cancellationFailure = runCatching { onActorClosing(endpoint) }.exceptionOrNull()
            val failure = actor.failure ?: cancellationFailure ?: CancellationException("native actor stopped")
            actor.turn?.let {
                val callbackFailure = runCatching { complete(it.completion, null, failure) }.exceptionOrNull()
                if (callbackFailure != null) {
                    val canceled = cancellationFailure
                    if (canceled == null) cancellationFailure = callbackFailure else canceled.addSuppressed(callbackFailure)
                }
            }
            actor.turn = null
            val queries = actor.queries
            actor.queries = null
            pendingQueries -= queries?.size ?: 0
            queries?.values?.forEach { it.future.completeExceptionally(failure) }
        } finally {
            // A callback may stop the host while its lifecycle observer is still on the stack.
            // Do not publish exclusive worker ownership until the entire observation unwinds.
            if (observationDepth == 0) {
                start()
            } else {
                (deferredCloseStarts ?: mutableListOf<() -> Unit>().also { deferredCloseStarts = it }).add(start)
            }
        }
        return result.copy()
    }

    /** Ordinary observations still run only on their owner; close progress is independent. */
    fun pump(
        maximumEvents: Int,
        maximumAttempts: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Int {
        checkOwner()
        require(maximumEvents >= 0 && maximumAttempts >= 0)
        closing.entries.removeAll { it.value.isDone }
        return dispatcher.pump(maximumEvents, maximumAttempts, deadlineNanos)
    }

    private fun observe(event: ExperimentalFleetEvent) {
        observationDepth++
        try {
            observeEvent(event)
        } finally {
            observationDepth--
            if (observationDepth == 0) {
                val starts = deferredCloseStarts
                deferredCloseStarts = null
                starts?.forEach { it() }
            }
        }
    }

    private fun observeEvent(event: ExperimentalFleetEvent) {
        when (event) {
            is ExperimentalFleetEvent.Outcome -> {
                val actor = machines[event.machine] ?: return
                observeOutcome(actor, event)
            }

            is ExperimentalFleetEvent.Quiescent -> {
                val actor = machines[event.machine] ?: return
                finishTurn(actor, event)
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
                onExternalEvent(actor.endpoint, event)
            }

            is ExperimentalFleetEvent.Compilation -> {
                val actor = machines[event.machine] ?: return
                actor.host.observeFleetOutcome(fleet, actor.machine, VmOutcome.CompilationRequested(event.request))
                onExternalEvent(actor.endpoint, event)
            }

            is ExperimentalFleetEvent.ScopeStopped -> {
                machines[event.machine]?.let { onExternalEvent(it.endpoint, event) }
            }

            is ExperimentalFleetEvent.Error -> {
                val machine = event.machine
                if (machine == null) error("native fleet failed: ${event.message}")
                val actor = machines[machine] ?: return
                actor.host.observeFleetFailure(fleet, machine, event.message)
                actor.failure = ProgramRuntimeActorFailedException(actor.endpoint, IllegalStateException(event.message.take(1024)))
                // Retain the close barrier in the lease even after the closing registry is reaped.
                // Only this machine is fenced; the pump keeps delivering other machines' events.
                actor.lease?.closeAsync() ?: stop(actor.endpoint)
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
                var world: MutableList<VmHostRequest>? = null
                for (request in outcome.requests) {
                    if (!isCurrent(actor, turn)) return
                    if (HostRequestRoutes.isRedstoneOutput(request) || HostRequestRoutes.isSound(request)) {
                        (world ?: mutableListOf<VmHostRequest>().also { world = it }).add(request)
                    } else {
                        val prepared = actor.host.prepareFleetAddonRequest(fleet, actor.machine, request)
                        if (prepared != null) {
                            handled = true
                            turn.submit(ProgramAddonAction.Request(prepared))
                        } else {
                            (unhandled ?: mutableListOf<VmHostRequest>().also { unhandled = it }).add(request)
                        }
                    }
                }
                if (!isCurrent(actor, turn) || turn.observationFailure != null) return
                world?.let { requests ->
                    val result = turn.submitWorld { actor.host.prepareFleetWorldRequest(fleet, actor.machine, requests) }
                    if (!isCurrent(actor, turn) || turn.observationFailure != null) return
                    if (result != null) {
                        handled = true
                        result.confirmedRedstoneOutput?.let { actor.host.observeFleetRedstoneOutput(fleet, actor.machine, it) }
                    } else {
                        (unhandled ?: mutableListOf<VmHostRequest>().also { unhandled = it }).addAll(requests)
                    }
                }
                if (!handled) {
                    onExternalEvent(actor.endpoint, event)
                } else {
                    unhandled?.let { remaining ->
                        val identities = remaining.mapTo(mutableSetOf()) { it.identity }
                        onExternalEvent(
                            actor.endpoint,
                            event.copy(
                                outcome =
                                    VmOutcome.HostRequestBatch(
                                        outcome.requests.filter {
                                            it.identity in
                                                identities
                                        },
                                    ),
                            ),
                        )
                    }
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
    ): Boolean = machines[actor.machine] === actor && actor.turn === turn

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
        check(actors.isEmpty()) { "native actors must be fenced for stop before closing dispatch" }
        closed = true
        return CompletableFuture.allOf(dispatcher.closeAsync(), closer.closeAsync()).thenApply { Unit }
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
        var lease: ProgramRuntimeActorLease? = null
        var failure: Throwable? = null
        var credit: ProgramRuntimeFrameCredit? = null
        var lastTurnTick = -1L
        var turn: Turn? = null
        var generation: Long? = null
        var queries: MutableMap<Long, Query>? = null
    }

    private class Turn(
        val id: ProgramRuntimeRequestId,
        val allowance: Long,
        val completion: ProgramRuntimeTurnCompletion,
        val isActive: () -> Boolean,
    ) : ProgramAddonLifecyclePort {
        var observationFailure: Exception? = null
            private set

        fun submitWorld(prepare: () -> ProgramWorldRequest?): ProgramWorldResult? {
            if (observationFailure != null || !isActive()) return null
            return try {
                prepare()?.let { (completion as ProgramRuntimeTurnObserver).worldRequest(it) }
            } catch (failure: Exception) {
                observationFailure = failure
                null
            }
        }

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
