/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldRequest
import ru.lazyhat.compukters.core.device.runtime.program.ProgramWorldResult
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetConfig
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/** Server-owner adapter. Rust alone schedules Guest work; collection reserves one shared tick budget. */
class NativeProgramRuntimeService(
    private val config: VmActorSchedulerConfig = VmActorSchedulerConfig(),
    private val perComputerEntitlement: Int = 131_072,
    private val safeInstructionCapacity: Long? = null,
    private val capacityGovernor: VmCapacityGovernor = VmCapacityGovernor(),
    onResultsReady: () -> Unit = {},
    private val tickBudget: ProgramTickBudget = ProgramTickBudget(),
    private val nanoTime: () -> Long = System::nanoTime,
) : ProgramRuntimeService() {
    private val owner = Thread.currentThread()
    private val registered = mutableMapOf<VmActorEndpoint, Registration>()
    private val turns = mutableMapOf<VmActorEndpoint, Turn>()
    private val waiting = ArrayDeque<Turn>()
    private val retries = ArrayDeque<Turn>()
    private var collecting: MutableList<Turn>? = null
    private var latestTick = -1L
    private var closed = false
    private var closing: CompletableFuture<Void>? = null

    // Observational accounting only. Native frame credit remains the sole execution authority.
    private val accounting = mutableMapOf<VmActorEndpoint, Usage>()
    private var allocation = PerComputerCapacityAllocation(emptyList(), 0, 0)
    private var waitingComputers = 0
    private var previousDemand = false
    private var lateTurn = false
    private var retiredTotal = 0L
    private var unusedTotal = 0L
    private var worldTotal = 0L
    private var rejectedInputs = 0L
    private var saturated = false
    private var lastPumpEvents = 0
    private var lastPumpNanos = 0L
    private val fleet: ExperimentalFleet
    private val runtime: NativeFleetActorRuntimeService

    init {
        require(perComputerEntitlement > 0)
        require(safeInstructionCapacity == null || safeInstructionCapacity >= 0)
        val fleetConfig =
            ExperimentalFleetConfig(
                config.workerCount,
                config.mailboxCapacity,
                Math.multiplyExact(config.resultCapacityPerWorker, config.workerCount),
                tickBudget.guestBudgetPerAdvance,
                tickBudget.maintenanceBudgetPerAdvance,
                tickBudget.hostRequestsPerTick,
                tickBudget.maximumAdvancesPerTick,
                NativeFleetCommandRequests.INSPECTION,
                coordinatedTurns = true,
            )
        fleet = VmRuntime.openExperimentalFleet(fleetConfig)
        runtime =
            try {
                NativeFleetActorRuntimeService(
                    fleet,
                    config.maximumActors,
                    Math.multiplyExact(config.resultCapacityPerWorker, config.workerCount),
                    config.mailboxCapacity,
                    ::checkOwner,
                    onResultsReady,
                    ::externalEvent,
                    nanoTime,
                    onActorClosing = ::actorClosing,
                )
            } catch (failure: Throwable) {
                fleet.close()
                throw failure
            }
    }

    internal override val carrierRuntime =
        object : ActorRuntimeService {
            override val redstoneInputCapacity: Int get() = runtime.redstoneInputCapacity

            override fun request(
                endpoint: VmActorEndpoint,
                command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
            ) = this@NativeProgramRuntimeService.request(endpoint, command)

            override fun submitHostCompletions(
                endpoint: VmActorEndpoint,
                completions: List<ProgramRuntimeHostCompletion>,
            ) = runtime.submitHostCompletions(endpoint, completions)

            override fun submitRedstoneInput(
                endpoint: VmActorEndpoint,
                packet: Int,
            ): Boolean {
                checkOwner()
                if (closed || endpoint in turns) return false
                return runtime.submitRedstoneInput(endpoint, packet)
            }

            override fun submitTurn(
                endpoint: VmActorEndpoint,
                worldTick: Long,
                effects: List<ProgramRuntimeActorEffect>,
                completion: ProgramRuntimeTurnCompletion,
            ): VmActorSubmission {
                checkOwner()
                require(worldTick >= 0)
                require(effects.all { it is ProgramRuntimeActorEffect.RedstoneInput || it is ProgramRuntimeActorEffect.CompleteAddons })
                if (closed) return VmActorSubmission.CLOSED
                val registration = registered[endpoint] ?: return VmActorSubmission.STALE_ENDPOINT
                if (endpoint in turns || worldTick <= registration.lastSubmittedTick) return VmActorSubmission.PERMIT_PENDING
                registration.lastSubmittedTick = worldTick
                val turn = Turn(endpoint, effects.toList(), completion)
                turns[endpoint] = turn
                val frame = collecting
                if (frame == null) waiting.addLast(turn) else frame.add(turn)
                return VmActorSubmission.ACCEPTED
            }

            override fun continueTurn(
                endpoint: VmActorEndpoint,
                worldTick: Long,
                effects: List<ProgramRuntimeActorEffect>,
                completion: ProgramRuntimeTurnCompletion,
            ): Boolean {
                checkOwner()
                if (closed || endpoint !in registered || endpoint in turns) return false
                val usage = accounting[endpoint]?.takeIf { it.tick == worldTick } ?: return false
                val turn =
                    Turn(endpoint, effects.toList(), completion).also {
                        it.tick = worldTick
                        it.deadline = usage.deadline
                    }
                turns[endpoint] = turn
                return if (runtime.continueTurn(endpoint, worldTick, effects, observer(turn))) {
                    true
                } else {
                    turns.remove(endpoint)
                    false
                }
            }

            override fun recordHostContinuationDelay(delayTicks: Long) = runtime.recordHostContinuationDelay(delayTicks)

            override fun recordCoalescedRedstoneInput() = runtime.recordCoalescedRedstoneInput()
        }

    override fun attachStandalone(
        endpoint: VmActorEndpoint,
        tickBudget: ProgramTickBudget,
    ): ProgramRuntimeActorLease? {
        checkOwner()
        require(tickBudget == this.tickBudget) { "native fleet requires uniform tick quotas" }
        check(!closed)
        val host = ProgramRuntimeHost(tickBudget)
        val lease = runtime.attach(endpoint, host) ?: return null.also { host.close() }
        registered[endpoint] = Registration(lease)
        return lease
    }

    override fun attachBootable(
        endpoint: VmActorEndpoint,
        store: WorldFileSystemStore,
        systemImage: VmSystemImage,
        tickBudget: ProgramTickBudget,
        compilerRouter: CompilerCompletionRouter?,
        initialRedstoneOutput: Int,
        addonHost: ProgramAddonHost?,
    ): ProgramRuntimeActorLease? {
        checkOwner()
        require(tickBudget == this.tickBudget) { "native fleet requires uniform tick quotas" }
        check(!closed)
        val lease =
            runtime.attachBootable(endpoint, store, systemImage, tickBudget, initialRedstoneOutput, addonHost, compilerRouter)
                ?: return null
        registered[endpoint] = Registration(lease)
        return lease
    }

    override fun request(
        endpoint: VmActorEndpoint,
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        checkOwner()
        var input = false
        val future =
            runtime.request(endpoint) { id ->
                command(id).also {
                    input = it is ProgramRuntimeActorCommand.SendTerminalKey || it is ProgramRuntimeActorCommand.SendTerminalText
                }
            }
        // The source is never exposed: cancellation cannot move observation updates off the owner.
        return future.handle { reply, failure ->
            checkOwner()
            if (failure != null) {
                if (input) rejectedInputs = add(rejectedInputs, 1)
                throw CompletionException(failure)
            }
            registered[endpoint]?.state = reply.state
            if (input && (reply.value as? ProgramRuntimeActorValue.Accepted)?.accepted == false) rejectedInputs = add(rejectedInputs, 1)
            reply
        }
    }

    override fun beginCapacityFrame(worldTick: Long) {
        checkOwner()
        require(worldTick >= 0 && worldTick > latestTick)
        check(collecting == null) { "previous capacity frame has not been flushed" }
        val stale = accounting.iterator()
        while (stale.hasNext()) {
            val entry = stale.next()
            if (entry.key !in turns) {
                unusedTotal = add(unusedTotal, entry.value.granted - entry.value.retired)
                stale.remove()
            }
        }
        latestTick = worldTick
        collecting = ArrayList<Turn>(waiting.size).also { while (waiting.isNotEmpty()) it.add(waiting.removeFirst()) }
    }

    override fun flushCapacityFrame() {
        checkOwner()
        val frame = collecting ?: return
        collecting = null
        allocation =
            PerComputerCapacityAllocator.allocate(
                frame
                    .filter {
                        registered[it.endpoint]?.state == ProgramRuntimeState.Running || it.effects.isNotEmpty()
                    }.map { it.endpoint },
                perComputerEntitlement,
                currentSafeCapacity(),
                latestTick,
            )
        waitingComputers = frame.size - allocation.grants.size
        previousDemand = allocation.grants.isNotEmpty()
        val grants = allocation.grants.associateBy { it.endpoint }
        val deadline = if (safeInstructionCapacity != null) Long.MAX_VALUE else nanoTime() + capacityGovernor.hostTimeBudgetNanos
        for (turn in frame) {
            turn.tick = latestTick
            turn.allowance = grants[turn.endpoint]?.retiredInstructionLimit?.toInt() ?: 0
            turn.deadline = deadline
            accounting[turn.endpoint] = Usage(latestTick, turn.allowance.toLong(), deadline)
            retries.addLast(turn)
        }
        retryTurns(frame.size, Long.MAX_VALUE)
    }

    override fun observePreviousCapacityFrame() {
        checkOwner()
        capacityGovernor.observeTick(previousDemand, lateTurn)
        previousDemand = false
        lateTurn = false
    }

    private fun retryTurns(
        maximumAttempts: Int,
        deadline: Long,
    ) {
        repeat(minOf(maximumAttempts, retries.size)) {
            if (deadline != Long.MAX_VALUE && nanoTime() - deadline >= 0) return
            val turn = retries.removeFirst()
            if (turns[turn.endpoint] !== turn) return@repeat
            if (!turn.granted) {
                if (!runtime.grantFrame(turn.endpoint, turn.tick, turn.allowance, turn.deadline)) {
                    retries.addLast(turn)
                    return@repeat
                }
                turn.granted = true
            }
            when (val admission = runtime.submitTurn(turn.endpoint, turn.tick, turn.effects, observer(turn))) {
                VmActorSubmission.ACCEPTED -> Unit
                VmActorSubmission.MAILBOX_FULL, VmActorSubmission.PERMIT_PENDING -> retries.addLast(turn)
                else -> finish(turn, null, ProgramRuntimeActorRequestException(admission))
            }
        }
    }

    private fun observer(turn: Turn): ProgramRuntimeTurnCompletion =
        if (turn.completion is ProgramRuntimeTurnObserver) {
            object : ProgramRuntimeTurnObserver {
                override fun addonAction(action: ProgramAddonAction) {
                    if (action is ProgramAddonAction.Request) observeWorld(turn)
                    turn.completion.addonAction(action)
                }

                override fun worldRequest(request: ProgramWorldRequest): ProgramWorldResult? {
                    observeWorld(turn)
                    return turn.completion.worldRequest(request)
                }

                override fun complete(
                    reply: ProgramRuntimeActorReply?,
                    failure: Throwable?,
                ) = finish(turn, reply, failure)
            }
        } else {
            ProgramRuntimeTurnCompletion { reply, failure -> finish(turn, reply, failure) }
        }

    private fun observeWorld(turn: Turn) {
        if (!turn.worldObserved) {
            turn.worldObserved = true
            worldTotal = add(worldTotal, 1)
        }
    }

    private fun finish(
        turn: Turn,
        reply: ProgramRuntimeActorReply?,
        failure: Throwable?,
    ) {
        if (turns[turn.endpoint] !== turn) return
        turns.remove(turn.endpoint)
        if (reply != null) {
            registered[turn.endpoint]?.state = reply.state
            retiredTotal = add(retiredTotal, reply.retiredInstructions)
            accounting[turn.endpoint]?.let { usage ->
                usage.retired = Math.addExact(usage.retired, reply.retiredInstructions)
                check(usage.retired <= usage.granted)
                if (usage.tick < latestTick) {
                    unusedTotal = add(unusedTotal, usage.granted - usage.retired)
                    accounting.remove(turn.endpoint)
                }
            }
            if (turn.deadline != Long.MAX_VALUE && nanoTime() - turn.deadline >= 0) lateTurn = true
        }
        turn.completion.complete(reply, failure)
    }

    private fun actorClosing(endpoint: VmActorEndpoint) {
        val failure = CancellationException("native actor fenced")
        turns[endpoint]?.let { turn ->
            collecting?.remove(turn)
            waiting.remove(turn)
            retries.remove(turn)
            finish(turn, null, failure)
        }
        accounting.remove(endpoint)?.let { unusedTotal = add(unusedTotal, it.granted - it.retired) }
        if (!runtime.contains(endpoint)) registered.remove(endpoint) else registered[endpoint]?.state = ProgramRuntimeState.Idle
    }

    private fun externalEvent(
        endpoint: VmActorEndpoint,
        event: ExperimentalFleetEvent,
    ) {
        if (event is ExperimentalFleetEvent.Outcome && event.outcome is VmOutcome.HostRequestBatch) {
            // All advertised capabilities must have a carrier route; fail only the offending actor.
            registered[endpoint]?.lease?.closeAsync()
        }
    }

    override fun pump(
        maximumEvents: Int,
        deadlineNanos: Long,
    ): Int {
        checkOwner()
        require(maximumEvents >= 0)
        val started = nanoTime()
        retryTurns(maximumEvents, deadlineNanos)
        val events = runtime.pump(maximumEvents, maximumEvents, deadlineNanos)
        lastPumpEvents = events
        lastPumpNanos = (nanoTime() - started).coerceAtLeast(0)
        return events
    }

    override fun calibratedCapacity(): Long = capacityGovernor.calibratedCapacity

    override fun currentSafeCapacity(): Long = safeInstructionCapacity ?: capacityGovernor.currentCapacity

    override fun capacityFallbackReason(): String? = capacityGovernor.fallbackReason

    override fun acceptCapacityCalibration(measurement: VmCapacityCalibration) {
        checkOwner()
        capacityGovernor.calibrated(measurement)
    }

    override fun rejectCapacityCalibration(reason: String) {
        checkOwner()
        capacityGovernor.calibrationFailed(reason)
    }

    override fun runtimeMetrics(): ProgramRuntimeActorMetrics {
        checkOwner()
        return ProgramRuntimeActorMetrics(
            scheduler = null,
            pendingRequests = runtime.pendingRequestCount + turns.size,
            deferredWorldRequests = null,
            totalDeferredWorldRequests = worldTotal,
            hostContinuationSamples = runtime.hostContinuationSamples,
            totalHostContinuationDelayTicks = runtime.totalHostContinuationDelayTicks,
            maximumHostContinuationDelayTicks = runtime.maximumHostContinuationDelayTicks,
            rejectedInputRequests = rejectedInputs,
            coalescedRedstoneInputs = runtime.coalescedRedstoneInputs,
            lastPumpEvents = lastPumpEvents,
            lastPumpNanos = lastPumpNanos,
            calibratedInstructionCapacity = calibratedCapacity(),
            currentInstructionCapacity = currentSafeCapacity(),
            measuredCapacityWorkers = capacityGovernor.measuredWorkers,
            capacityFallbackReason = capacityFallbackReason(),
            runnableComputersLastFrame = allocation.grants.size,
            waitingComputersLastFrame = waitingComputers,
            throttledComputersLastFrame = allocation.grants.count { it.retiredInstructionLimit < perComputerEntitlement },
            requestedInstructionsLastFrame = allocation.requestedInstructions,
            reservedInstructionsLastFrame = allocation.reservedInstructions,
            missedInstructionsLastFrame = allocation.missedInstructions,
            retiredInstructionsTotal = retiredTotal,
            unusedReservationsTotal = unusedTotal,
            countersSaturated = saturated,
            backend = VmExecutionBackend.NATIVE,
            nativeActors = registered.size,
            nativeMaximumActors = config.maximumActors,
        )
    }

    override fun close() {
        checkOwner()
        if (!closed) {
            closed = true
            val closes = registered.values.toList().map { it.lease.closeAsync() }
            // Fencing is synchronous; native detach/close and waiter shutdown finish independently.
            // A timeout must not abandon the final fleet owner or make a second close ineffective.
            val stopped = runtime.closeAsync()
            closing = CompletableFuture.allOf(*closes.toTypedArray(), stopped).whenComplete { _, _ -> fleet.close() }
        }
        closing?.get(config.shutdownTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "native server runtime requires its owner thread" }

    private fun add(
        value: Long,
        delta: Long,
    ): Long {
        require(delta >= 0)
        if (delta > Long.MAX_VALUE - value) {
            saturated = true
            return Long.MAX_VALUE
        }
        return value + delta
    }

    private class Registration(
        val lease: ProgramRuntimeActorLease,
        var state: ProgramRuntimeState = ProgramRuntimeState.Idle,
        var lastSubmittedTick: Long = -1,
    )

    private class Usage(
        val tick: Long,
        val granted: Long,
        val deadline: Long,
        var retired: Long = 0,
    )

    private class Turn(
        val endpoint: VmActorEndpoint,
        val effects: List<ProgramRuntimeActorEffect>,
        val completion: ProgramRuntimeTurnCompletion,
    ) {
        var tick = -1L
        var allowance = 0
        var deadline = Long.MAX_VALUE
        var granted = false
        var worldObserved = false
    }
}
