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
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetCommandTransport
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetConfig
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.FleetExecutionMode
import ru.lazyhat.compukters.lang.runtime.vm.FleetWaitReason
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
    override val supportsCommandOnlyPump: Boolean get() = true

    // One bounded pass over the configured outbox capacity, independently of result delivery.
    private val maximumCommandAttempts = Math.multiplyExact(config.resultCapacityPerWorker, config.workerCount)
    private val owner = Thread.currentThread()
    private val registered = mutableMapOf<VmActorEndpoint, Registration>()
    private val frames = mutableMapOf<VmActorEndpoint, Frame>()
    private val waiting = ArrayDeque<Frame>()
    private val retries = ArrayDeque<Frame>()
    private var collecting: MutableList<Frame>? = null
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
                maximumCommandAttempts,
                tickBudget.guestBudgetPerAdvance,
                tickBudget.maintenanceBudgetPerAdvance,
                tickBudget.hostRequestsPerTick,
                tickBudget.maximumAdvancesPerTick,
                NativeFleetCommandRequests.INSPECTION,
                executionMode = FleetExecutionMode.AUTONOMOUS_FRAMES,
            )
        fleet = VmRuntime.openExperimentalFleet(fleetConfig)
        runtime =
            try {
                require(fleet is ExperimentalFleetCommandTransport) { "native fleet command batching is required" }
                NativeFleetActorRuntimeService(
                    fleet,
                    config.maximumActors,
                    maximumCommandAttempts,
                    config.mailboxCapacity,
                    ::checkOwner,
                    onResultsReady,
                    ::externalEvent,
                    nanoTime,
                    onActorClosing = ::actorClosing,
                    onFrameProgress = ::observeProgress,
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
                if (closed) return false
                return runtime.submitRedstoneInput(endpoint, packet)
            }

            override fun submitFrame(
                endpoint: VmActorEndpoint,
                worldTick: Long,
                effects: List<ProgramRuntimeActorEffect>,
                observer: ProgramRuntimeActorObserver,
            ): VmActorSubmission {
                checkOwner()
                require(worldTick >= 0)
                require(effects.all { it is ProgramRuntimeActorEffect.RedstoneInput || it is ProgramRuntimeActorEffect.CompleteAddons })
                if (closed) return VmActorSubmission.CLOSED
                val registration = registered[endpoint] ?: return VmActorSubmission.STALE_ENDPOINT
                if (endpoint in frames || worldTick <= registration.lastSubmittedTick) return VmActorSubmission.PERMIT_PENDING
                registration.lastSubmittedTick = worldTick
                val frame = Frame(endpoint, effects.toList(), observer(endpoint, observer))
                frames[endpoint] = frame
                val collecting = collecting
                if (collecting == null) waiting.addLast(frame) else collecting.add(frame)
                return VmActorSubmission.ACCEPTED
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
        latestTick = worldTick
        collecting = ArrayList<Frame>(waiting.size).also { while (waiting.isNotEmpty()) it.add(waiting.removeFirst()) }
    }

    override fun flushCapacityFrame() {
        checkOwner()
        val frame = collecting ?: return
        collecting = null
        allocation =
            PerComputerCapacityAllocator.allocate(
                frame
                    .filter {
                        registered[it.endpoint]?.let { registration ->
                            registration.state == ProgramRuntimeState.Running ||
                                registration.needsFrame
                        } ==
                            true ||
                            it.effects.isNotEmpty()
                    }.map { it.endpoint },
                perComputerEntitlement,
                currentSafeCapacity(),
                latestTick,
            )
        waitingComputers = frame.size - allocation.grants.size
        previousDemand = allocation.grants.isNotEmpty()
        val grants = allocation.grants.associateBy { it.endpoint }
        val deadline = nanoTime() + 50_000_000L
        for (frame in frame) {
            frame.tick = latestTick
            frame.allowance = grants[frame.endpoint]?.retiredInstructionLimit?.toInt() ?: 0
            frame.deadline = deadline
            retries.addLast(frame)
        }
        retryFrames(frame.size, Long.MAX_VALUE)
    }

    override fun observePreviousCapacityFrame() {
        checkOwner()
        capacityGovernor.observeTick(previousDemand, lateTurn)
        previousDemand = false
        lateTurn = false
    }

    private fun retryFrames(
        maximumAttempts: Int,
        deadline: Long,
    ) {
        repeat(minOf(maximumAttempts, retries.size)) {
            if (deadline != Long.MAX_VALUE && nanoTime() - deadline >= 0) return
            val frame = retries.removeFirst()
            if (frames[frame.endpoint] !== frame) return@repeat
            when (
                val admission =
                    runtime.submitFrame(
                        frame.endpoint,
                        frame.tick,
                        frame.allowance,
                        if (safeInstructionCapacity != null) Long.MAX_VALUE else capacityGovernor.hostTimeBudgetNanos,
                        frame.deadline,
                        frame.effects,
                        frame.observer,
                    )
            ) {
                VmActorSubmission.ACCEPTED -> {
                    frames.remove(frame.endpoint)
                }

                VmActorSubmission.MAILBOX_FULL, VmActorSubmission.PERMIT_PENDING -> {
                    retries.addLast(frame)
                }

                else -> {
                    frames.remove(frame.endpoint)
                    frame.observer.failed(ProgramRuntimeActorRequestException(admission))
                }
            }
        }
    }

    private fun observer(
        endpoint: VmActorEndpoint,
        source: ProgramRuntimeActorObserver,
    ): ProgramRuntimeActorObserver {
        val registration = registered.getValue(endpoint)
        if (registration.observerSource !== source) {
            registration.observerSource = source
            registration.observer =
                object : ProgramRuntimeActorObserver {
                    private var failed = false

                    override fun addonAction(action: ProgramAddonAction) {
                        if (failed) return
                        if (action is ProgramAddonAction.Request) observeWorld(registration)
                        source.addonAction(action)
                    }

                    override fun worldRequest(request: ProgramWorldRequest): ProgramWorldResult? {
                        if (failed) return null
                        observeWorld(registration)
                        return source.worldRequest(request)
                    }

                    override fun updated(observation: ProgramRuntimeActorObservation) {
                        if (failed) return
                        registration.state = observation.state
                        registration.needsFrame = observation.needsFrame
                        source.updated(observation)
                    }

                    override fun failed(failure: Throwable) {
                        if (failed) return
                        failed = true
                        source.failed(failure)
                    }
                }
        }
        return checkNotNull(registration.observer)
    }

    private fun observeWorld(registration: Registration) {
        if (registration.worldObservedTick != latestTick) {
            registration.worldObservedTick = latestTick
            worldTotal = add(worldTotal, 1)
        }
    }

    private fun observeProgress(
        endpoint: VmActorEndpoint,
        event: ExperimentalFleetEvent.Quiescent,
    ) {
        val progress = requireNotNull(event.frame)
        val previous = accounting[endpoint]
        if (previous != null && event.tick < previous.tick) return
        if (previous != null && event.tick == previous.tick) {
            check(event.retired >= previous.retired) { "native retirement count moved backwards" }
            retiredTotal = add(retiredTotal, event.retired - previous.retired)
            previous.retired = event.retired
        } else {
            previous?.let { unusedTotal = add(unusedTotal, it.granted - it.retired) }
            retiredTotal = add(retiredTotal, event.retired)
            accounting[endpoint] = Usage(event.tick, Math.addExact(event.retired, progress.instructionsLeft.toLong()), event.retired)
        }
        // Host and queue waiting never count as a compute overrun.
        if (progress.reason == FleetWaitReason.EXECUTION_TIME ||
            progress.executionNanos > capacityGovernor.hostTimeBudgetNanos
        ) {
            lateTurn = true
        }
    }

    private fun actorClosing(endpoint: VmActorEndpoint) {
        val failure = CancellationException("native actor fenced")
        frames.remove(endpoint)?.let { frame ->
            collecting?.remove(frame)
            waiting.remove(frame)
            retries.remove(frame)
            frame.observer.failed(failure)
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
        retryFrames(maximumEvents, deadlineNanos)
        val events = runtime.pump(maximumEvents, maximumCommandAttempts, deadlineNanos)
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
            pendingRequests = runtime.pendingRequestCount + frames.size,
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
    ) {
        var observerSource: ProgramRuntimeActorObserver? = null
        var observer: ProgramRuntimeActorObserver? = null
        var needsFrame = false
        var worldObservedTick = -1L
    }

    private class Usage(
        val tick: Long,
        val granted: Long,
        var retired: Long = 0,
    )

    private class Frame(
        val endpoint: VmActorEndpoint,
        val effects: List<ProgramRuntimeActorEffect>,
        val observer: ProgramRuntimeActorObserver,
    ) {
        var tick = -1L
        var allowance = 0
        var deadline = Long.MAX_VALUE
    }
}
