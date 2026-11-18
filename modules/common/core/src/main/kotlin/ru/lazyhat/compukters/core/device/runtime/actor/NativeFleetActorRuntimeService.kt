/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.program.HostRequestRoutes
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonLifecyclePort
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldRequest
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldResult
import ru.lazyhat.compukters.core.device.runtime.program.prepareCommandRequest
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetDeploymentFailure
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetDeploymentResult
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * Native execution service. A lazy bootstrap worker prepares idle hosts using the
 * boot/restoration handler. Resource rebinding precedes transfer; Rust then owns the session until stop.
 * No JVM worker advances Guest instructions. Frame grants come from the capacity owner.
 *
 * Callers configure [NativeFleetCommandRequests.INSPECTION] for batch quota accounting.
 * The carrier streams
 * addon replies, redstone confirmations and world replies into one bounded ordered outbox,
 * independently of the next execution grant. Legacy deferred world effects reject before admission.
 * Addon/world requests and program lifecycle actions stream to a turn observer on the owner thread;
 * final-only callers and unsupported host events use [onExternalEvent]. Neither path
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
    private var bootstrapFirst = true
    private var observationDepth = 0
    private var deferredCloseStarts: MutableList<() -> Unit>? = null
    private var nextRequest = 0L
    private var pendingQueries = 0

    // Only requests with pending candidate disposal are queued, never idle machines.
    private val deploymentCleanup = java.util.ArrayDeque<Pair<Long, Long>>()
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
            nanoTime,
        )

    private val compilations = NativeFleetCompilations(dispatcher.outbox, nanoTime)
    private val commandRequests =
        NativeFleetCommandRequests(dispatcher.outbox, maximumRequests, maximumRequestsPerActor, byteLimits, nanoTime)

    private val bootstrap =
        NativeFleetBootstrap(
            checkOwner,
            onResultsReady,
            dispatcher.outbox::reserveBootstrap,
            { entry, tick ->
                val machine = entry.host.transferToFleet(fleet, tick)
                val actor = Actor(entry.endpoint, machine, entry.host)
                actor.lease = entry.lease
                check(actors.put(entry.endpoint, actor) == null)
                check(machines.put(machine, actor) == null)
            },
            onActorClosing,
            nanoTime,
            ::prepareLeaseClose,
        )

    internal val pendingRequestCount: Int get() = pendingQueries + bootstrap.pendingRequests

    internal fun contains(endpoint: VmActorEndpoint): Boolean {
        checkOwner()
        return endpoint in actors || bootstrap.contains(endpoint)
    }

    /** Idle hosts are prepared off-thread and become native actors only after the restoration handshake. */
    fun attach(
        endpoint: VmActorEndpoint,
        host: ProgramRuntimeHost,
    ): ProgramRuntimeActorLease? {
        checkOwner()
        check(!closed) { "native actor service is closed" }
        closer.checkHealthy()
        if (!canAttach(endpoint)) return null
        host.requireIdleForBootstrap()
        return bootstrap.attach(endpoint, host)
    }

    fun attachBootable(
        endpoint: VmActorEndpoint,
        store: WorldFileSystemStore,
        systemImage: VmSystemImage,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        initialRedstoneOutput: Int = 0,
        addonHost: ProgramAddonHost? = null,
        compilerRouter: CompilerCompletionRouter? = null,
    ): ProgramRuntimeActorLease? {
        checkOwner()
        if (!canAttach(endpoint)) return null
        val host =
            ProgramRuntimeHost(
                store = store,
                computerId = endpoint.computerId,
                systemImage = systemImage,
                tickBudget = tickBudget,
                initialRedstoneOutput = initialRedstoneOutput,
                compilerRouter = compilerRouter,
                addonCapabilitySchemas = addonHost?.capabilitySchemas.orEmpty(),
            )
        return attach(endpoint, host)
    }

    private fun canAttach(endpoint: VmActorEndpoint): Boolean {
        closing.entries.removeAll { it.value.isDone }
        return actors.size + closing.size + bootstrap.size < maximumActors &&
            actors.keys.none { it.computerId == endpoint.computerId } &&
            closing.keys.none { it.computerId == endpoint.computerId } && !bootstrap.containsComputer(endpoint)
    }

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
        if (!canAttach(endpoint) || machine in machines) return false
        val actor = Actor(endpoint, machine, host)
        actors[endpoint] = actor
        machines[machine] = actor
        return true
    }

    /** Adopts an already transferred host; idle hosts use the bootstrap overload. */
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
        if (bootstrap.contains(endpoint)) return bootstrap.prepareClose(endpoint, request)
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

    override fun submitRedstoneInput(
        endpoint: VmActorEndpoint,
        packet: Int,
    ): Boolean {
        checkOwner()
        val checked = RedstoneWire.requireInputPacket(packet)
        if (closed || endpoint in closing) return false
        val actor = actors[endpoint] ?: return false
        if (actor.turn != null || actor.credit?.inFlight == true) return false
        if (!dispatcher.outbox.offer(actor.machine, NativeFleetOutbox.Command.RedstoneInput(checked))) return false
        actor.host.observeFleetRedstoneInput(fleet, actor.machine, checked)
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
            "native world completions require the streamed host completion path"
        }
        if (endpoint in closing) return VmActorSubmission.CLOSED
        val actor = actors[endpoint] ?: return VmActorSubmission.STALE_ENDPOINT
        val credit = actor.credit ?: return VmActorSubmission.MAILBOX_FULL
        if (actor.turn != null || credit.inFlight) return VmActorSubmission.PERMIT_PENDING
        if (credit.worldTick != worldTick ||
            (continuation && credit.deadlineNanos != Long.MAX_VALUE && nanoTime() - credit.deadlineNanos >= 0)
        ) {
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
        val allowance = credit.remaining
        val grant = NativeFleetOutbox.Command.Grant(worldTick, allowance.toInt(), credit.deadlineNanos)
        val id = requestId()
        val accepted =
            if (effects.isEmpty()) {
                dispatcher.outbox.offer(actor.machine, grant)
            } else {
                val commands = ArrayList<NativeFleetOutbox.Command>(commandCount)
                effects.forEach { effect ->
                    when (effect) {
                        is ProgramRuntimeActorEffect.RedstoneInput -> {
                            commands +=
                                NativeFleetOutbox.Command.RedstoneInput(RedstoneWire.requireInputPacket(effect.packet))
                        }

                        is ProgramRuntimeActorEffect.CompleteAddons -> {
                            effect.completions.forEach {
                                commands += NativeFleetOutbox.Command.Reply(it.identity, it.response)
                            }
                        }

                        else -> {
                            error("unsupported native effect")
                        }
                    }
                }
                commands += grant
                dispatcher.outbox.offer(actor.machine, commands)
            }
        if (!accepted) return VmActorSubmission.MAILBOX_FULL
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
        if (bootstrap.contains(endpoint)) {
            if (pendingQueries + bootstrap.pendingRequests >= maximumRequests) {
                return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL))
            }
            return bootstrap.request(endpoint, prepared)
        }
        val actor =
            actors[endpoint]
                ?: return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.STALE_ENDPOINT))
        if (pendingQueries + bootstrap.pendingRequests >= maximumRequests || (actor.queries?.size ?: 0) >= maximumRequestsPerActor) {
            return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL))
        }
        if (prepared is ProgramRuntimeActorCommand.Shutdown || prepared is ProgramRuntimeActorCommand.Reboot) {
            return powerControl(actor, prepared)
        }
        val outgoing =
            try {
                NativeFleetRequest.prepare(prepared)?.also { dispatcher.outbox.requireFits(it) }
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
        val consumedToken =
            when (prepared) {
                is ProgramRuntimeActorCommand.Deploy -> prepared.token.value
                is ProgramRuntimeActorCommand.DiscardDeployment -> prepared.token.value
                else -> null
            }
        if (consumedToken != null && actor.queries?.values?.any {
                it.deploymentCandidate == consumedToken || it.cleanupCandidate == consumedToken
            } == true
        ) {
            // A first install consumes the public token on admission, even while Rust retains
            // its candidate until installation or disposal. This is in-flight request state only.
            val value =
                if (prepared is ProgramRuntimeActorCommand.DiscardDeployment) {
                    ProgramRuntimeActorValue.Accepted(false)
                } else {
                    ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.InvalidRequest("unknown or consumed deployment token"))
                }
            return CompletableFuture.completedFuture(ProgramRuntimeActorReply(id, actor.host.state, value, actor.generation))
        }
        if (!dispatcher.outbox.offer(actor.machine, outgoing)) {
            return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL))
        }
        val result = CompletableFuture<ProgramRuntimeActorReply>()
        val queries = actor.queries ?: mutableMapOf<Long, Query>().also { actor.queries = it }
        queries[id.value] = Query(id, outgoing.response, result, outgoing.deploymentCandidate)
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
    fun stop(endpoint: VmActorEndpoint): CompletableFuture<Long>? {
        checkOwner()
        return bootstrap.stop(endpoint)?.thenApply { it ?: 0L } ?: finish(endpoint, null, null)
    }

    private fun powerControl(
        actor: Actor,
        command: ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        closer.checkHealthy()
        val predecessor = CompletableFuture<Long>()
        val result =
            try {
                bootstrap.transition(
                    actor.endpoint,
                    actor.host,
                    actor.lease,
                    maxOf(actor.host.lastObservedWorldTick, actor.lastTurnTick),
                    command,
                    predecessor,
                ) ?: return CompletableFuture.failedFuture(ProgramRuntimeActorRequestException(VmActorSubmission.MAILBOX_FULL))
            } catch (failure: IllegalArgumentException) {
                return CompletableFuture.failedFuture(failure)
            }
        // The bounded bootstrap entry now owns this endpoint and the same lease. Fence native
        // observations before callbacks; its worker cannot use the host until retirement completes.
        actors.remove(actor.endpoint)
        retire(actor, predecessor, finalState = ProgramRuntimeState.Idle)
        return result
    }

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
        val result = CompletableFuture<Long>()
        closing[endpoint] = result
        retire(actor, result, checkpointTick, payload, saveExecution)
        return result.copy()
    }

    /** Common exclusive handoff for terminal closes and reusable power transitions. */
    private fun retire(
        actor: Actor,
        result: CompletableFuture<Long>,
        checkpointTick: Long? = null,
        payload: NativeFleetOutbox.CheckpointPayload? = null,
        saveExecution: Boolean = true,
        finalState: ProgramRuntimeState = ProgramRuntimeState.Closed,
    ) {
        machines.remove(actor.machine)
        commandRequests.cancel(actor.machine)
        deploymentCleanup.removeIf { it.first == actor.machine }
        val compilationCancellation = compilations.cancel(actor.machine)
        val commands = dispatcher.outbox.handoffForClose(actor.machine)
        var cancellationFailure: Throwable? = compilationCancellation
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
        val start = closer.reserve(actor.machine, actor.host, commands, native, checkpointTick, payload, saveExecution, finalState)
        try {
            runCatching { onActorClosing(actor.endpoint) }.exceptionOrNull()?.let { failure ->
                val previous = cancellationFailure
                if (previous == null) cancellationFailure = failure else previous.addSuppressed(failure)
            }
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
        // Continuous startup must leave observation capacity for already active machines.
        val bootstrapAllowance = maximumEvents / 2 + if (bootstrapFirst) maximumEvents % 2 else 0
        if (maximumEvents > 0) bootstrapFirst = !bootstrapFirst
        val prepared = bootstrap.pump(bootstrapAllowance, deadlineNanos, maximumAttempts)
        val remaining = maximumAttempts - bootstrap.lastTransferAttempts
        retryDeploymentCleanup(remaining, deadlineNanos)
        compilations.pump(remaining, deadlineNanos)
        commandRequests.pump(remaining, deadlineNanos)
        return prepared + dispatcher.pump(maximumEvents - prepared, remaining, deadlineNanos)
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

            is ExperimentalFleetEvent.Resources -> {
                machines[event.machine]?.let { finishQuery(it, event.request, event) }
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

            is ExperimentalFleetEvent.Deployment -> {
                machines[event.machine]?.let { finishQuery(it, event.request, event) }
            }

            is ExperimentalFleetEvent.FileFailure -> {
                machines[event.machine]?.let { finishQuery(it, event.request, event) }
            }

            is ExperimentalFleetEvent.Inspection -> {
                val actor = machines[event.machine] ?: return
                actor.host.requireFleetOwnership(fleet, actor.machine)
                val prepared = prepareCommandRequest(event.request, actor.host.currentProgramScope)
                if (prepared != null) commandRequests.inspect(actor.machine, prepared) else onExternalEvent(actor.endpoint, event)
            }

            is ExperimentalFleetEvent.Compilation -> {
                val actor = machines[event.machine] ?: return
                actor.host.requireFleetOwnership(fleet, actor.machine)
                compilations.request(actor.machine, actor.host, event.request)
            }

            is ExperimentalFleetEvent.ScopeStopped -> {
                compilations.cancelScope(event.machine, event.scope)
                commandRequests.cancel(event.machine, event.scope)
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
        when (val outcome = event.outcome) {
            is VmOutcome.ProcessExited -> {
                commandRequests.cancel(actor.machine, outcome.programId)
            }

            is VmOutcome.ProcessReplaced -> {
                commandRequests.cancel(actor.machine, outcome.previousProgramId)
            }

            is VmOutcome.Halted, is VmOutcome.Crashed, is VmOutcome.Faulted, is VmOutcome.QuotaExhausted,
            is VmOutcome.AllocationExhausted, is VmOutcome.UncaughtException, is VmOutcome.HostFailed,
            -> {
                commandRequests.cancel(
                    actor.machine,
                )
            }

            else -> {
                Unit
            }
        }
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
                        val prepared =
                            actor.host.prepareFleetAddonRequest(
                                fleet,
                                actor.machine,
                                request,
                                commandRequests.take(actor.machine, request.identity, actor.host.currentProgramScope),
                            )
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
        val query = actor.queries?.get(request) ?: return
        if (query.cleanupCandidate != null) {
            val result = (event as? ExperimentalFleetEvent.Deployment)?.result
            if (result != ExperimentalFleetDeploymentResult.Discarded &&
                result != ExperimentalFleetDeploymentResult.Failed(ExperimentalFleetDeploymentFailure.INVALID_CANDIDATE)
            ) {
                // Candidate disposal is an ownership barrier, not an ignorable best-effort send.
                observeEvent(ExperimentalFleetEvent.Error(actor.machine, "native deployment cleanup failed: $event"))
                return
            }
            releaseQuery(actor, request, query)
            query.value?.let { completeQuery(actor, query, it) }
            return
        }
        val value =
            try {
                val decoded =
                    if (query.response == NativeFleetRequest.Response.RESOURCES) {
                        check(event is ExperimentalFleetEvent.Resources) { "unexpected native resource response: $event" }
                        ProgramRuntimeActorValue.ResourceSnapshotValue(
                            actor.host.fleetResourceSnapshot(fleet, actor.machine, event.snapshot),
                        )
                    } else {
                        query.response.decode(event)
                    }
                decoded.also { value ->
                    if (value is ProgramRuntimeActorValue.FileSystemGeneration) actor.generation = value.generation
                    if (value is ProgramRuntimeActorValue.DeployedRevision) {
                        actor.generation =
                            (value.revision as? VmExecutableRevision.Present)?.generation
                                ?: actor.generation
                    }
                    if (value is ProgramRuntimeActorValue.Accepted && value.accepted &&
                        (
                            query.response == NativeFleetRequest.Response.INPUT ||
                                query.response == NativeFleetRequest.Response.INTERRUPT_INPUT
                        )
                    ) {
                        actor.host.observeFleetTerminalInput(
                            fleet,
                            actor.machine,
                            query.response == NativeFleetRequest.Response.INTERRUPT_INPUT,
                        )
                    }
                }
            } catch (error: Exception) {
                releaseQuery(actor, request, query)
                query.future.completeExceptionally(error)
                return
            }
        if (value is ProgramRuntimeActorValue.Rejected && query.response == NativeFleetRequest.Response.DEPLOY &&
            query.deploymentCandidate != null
        ) {
            scheduleDeploymentCleanup(actor, request, query, query.deploymentCandidate, value)
            return
        }
        // Release before callbacks so prepare.thenCompose(deploy) works even with a one-request limit.
        releaseQuery(actor, request, query)
        val delivered = completeQuery(actor, query, value)
        if (!delivered && value is ProgramRuntimeActorValue.DeploymentPrepared && value.token != null &&
            machines[actor.machine] === actor
        ) {
            // A canceled prepare has no recipient; retain its original slot until disposal is acknowledged.
            val queries = actor.queries ?: mutableMapOf<Long, Query>().also { actor.queries = it }
            queries[request] = query
            pendingQueries++
            scheduleDeploymentCleanup(actor, request, query, value.token.value, null)
        }
    }

    private fun completeQuery(
        actor: Actor,
        query: Query,
        value: ProgramRuntimeActorValue,
    ): Boolean = query.future.complete(ProgramRuntimeActorReply(query.id, actor.host.state, value, actor.generation))

    private fun releaseQuery(
        actor: Actor,
        request: Long,
        query: Query,
    ) {
        if (actor.queries?.get(request) !== query) return // A completion callback may already have stopped the actor.
        actor.queries?.remove(request)
        pendingQueries--
        if (actor.queries?.isEmpty() == true) actor.queries = null
    }

    private fun scheduleDeploymentCleanup(
        actor: Actor,
        request: Long,
        query: Query,
        candidate: Long,
        value: ProgramRuntimeActorValue?,
    ) {
        query.cleanupCandidate = candidate
        query.value = value
        val cleanupId = requestId().value
        val queries = checkNotNull(actor.queries)
        check(queries.remove(request) === query)
        queries[cleanupId] = query
        deploymentCleanup.addLast(actor.machine to cleanupId)
    }

    private fun retryDeploymentCleanup(
        maximumAttempts: Int,
        deadlineNanos: Long,
    ) {
        var attempts = 0
        val count = minOf(maximumAttempts, deploymentCleanup.size)
        while (attempts < count && (deadlineNanos == Long.MAX_VALUE || nanoTime() - deadlineNanos < 0)) {
            val (machine, request) = deploymentCleanup.removeFirst()
            attempts++
            val actor = machines[machine] ?: continue
            val query = actor.queries?.get(request) ?: continue
            val command =
                NativeFleetRequest.prepare(
                    ProgramRuntimeActorCommand.DiscardDeployment(
                        ProgramRuntimeRequestId(request),
                        ProgramDeploymentToken(checkNotNull(query.cleanupCandidate)),
                    ),
                )!!
            if (!dispatcher.outbox.offer(machine, command)) deploymentCleanup.addLast(machine to request)
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
        check(actors.isEmpty() && bootstrap.isEmpty) { "native actors must be fenced for stop before closing dispatch" }
        closed = true
        return CompletableFuture.allOf(bootstrap.closeAsync(), dispatcher.closeAsync(), closer.closeAsync()).thenApply { Unit }
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
        val id: ProgramRuntimeRequestId,
        val response: NativeFleetRequest.Response,
        val future: CompletableFuture<ProgramRuntimeActorReply>,
        val deploymentCandidate: Long?,
    ) {
        var cleanupCandidate: Long? = null
        var value: ProgramRuntimeActorValue? = null
    }
}
