/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonHost
import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.program.EmptyProgramAddonHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonLifecyclePort
import ru.lazyhat.compukters.core.device.runtime.program.ProgramAddonRequestPort
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneHostPort
import ru.lazyhat.compukters.core.device.runtime.program.SoundCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.SoundHostPort
import ru.lazyhat.compukters.core.device.runtime.program.SoundRequest
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Owns runtime actors and is the only supported cross-thread entry point to their native sessions. */
class ProgramRuntimeActorService(
    config: VmActorSchedulerConfig = VmActorSchedulerConfig(),
    private val perComputerEntitlement: Int = 131_072,
    private val safeInstructionCapacity: Long? = null,
    private val capacityGovernor: VmCapacityGovernor = VmCapacityGovernor(),
    onResultsReady: () -> Unit = {},
    private val capacityFramesRequired: Boolean = false,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    init {
        require(perComputerEntitlement > 0) { "per-computer entitlement must be positive" }
        require(safeInstructionCapacity == null || safeInstructionCapacity >= 0) {
            "safe instruction capacity must not be negative"
        }
    }

    private val frameLock = Any()
    private var capacityFrame: CapacityFrame? = null
    private var latestCapacityTick = -1L
    private val frameCredits = mutableMapOf<VmActorEndpoint, FrameCredit>()
    private val awaitingCapacity = mutableListOf<PendingTurn>()
    private val closed = AtomicBoolean()
    internal var lastCapacityAllocation = PerComputerCapacityAllocation(emptyList(), 0, 0)
        private set
    internal val redstoneInputCapacity = config.mailboxCapacity
    private val scheduler =
        VmActorScheduler<ProgramRuntimeActorMessage, ProgramRuntimeTickPermit, ProgramRuntimeActorReply>(config, onResultsReady)
    private val pending = ConcurrentHashMap<RequestAddress, PendingRequest>()
    private val reservations = ConcurrentHashMap<RequestAddress, ReservedTurn>()
    private val knownRunnable = ConcurrentHashMap<VmActorEndpoint, Boolean>()
    private val deferredWorldRequests = ConcurrentHashMap.newKeySet<VmActorEndpoint>()
    private val nextRequestId = AtomicLong()
    private val totalDeferredWorldRequests = AtomicLong()
    private val hostContinuationSamples = AtomicLong()
    private val totalHostContinuationDelayTicks = AtomicLong()
    private val maximumHostContinuationDelayTicks = AtomicLong()
    private val rejectedInputRequests = AtomicLong()
    private val coalescedRedstoneInputs = AtomicLong()
    private val lastPumpEvents = AtomicInteger()
    private val lastPumpNanos = AtomicLong()
    private val lastRunnableComputers = AtomicInteger()
    private val lastWaitingComputers = AtomicInteger()
    private val lastThrottledComputers = AtomicInteger()
    private val totalRetiredInstructions = AtomicLong()
    private val totalUnusedReservations = AtomicLong()
    private val countersSaturated = AtomicBoolean()
    private var previousFrameHadDemand = false
    private var observedLateTurn = false

    fun registerStandalone(
        endpoint: VmActorEndpoint,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
    ): Boolean = attachStandalone(endpoint, tickBudget) != null

    fun attachStandalone(
        endpoint: VmActorEndpoint,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
    ): ProgramRuntimeActorLease? = attach(endpoint, ProgramRuntimeHost(tickBudget))

    fun registerBootable(
        endpoint: VmActorEndpoint,
        store: WorldFileSystemStore,
        romImage: ByteArray,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        compilerRouter: CompilerCompletionRouter? = null,
        initialRedstoneOutput: Int = 0,
        addonHost: ProgramAddonHost? = null,
    ): Boolean = attachBootable(endpoint, store, romImage, tickBudget, compilerRouter, initialRedstoneOutput, addonHost) != null

    fun attachBootable(
        endpoint: VmActorEndpoint,
        store: WorldFileSystemStore,
        romImage: ByteArray,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        compilerRouter: CompilerCompletionRouter? = null,
        initialRedstoneOutput: Int = 0,
        addonHost: ProgramAddonHost? = null,
    ): ProgramRuntimeActorLease? {
        val port = ActorRedstoneHostPort()
        val soundPort = ActorSoundHostPort()
        val effectiveAddonHost = addonHost ?: EmptyProgramAddonHost
        val addonPort = ActorAddonRequestPort()
        val host =
            ProgramRuntimeHost(
                store = store,
                computerId = endpoint.computerId,
                romImage = romImage,
                tickBudget = tickBudget,
                compilerRouter = compilerRouter,
                redstoneHostPort = port,
                soundHostPort = soundPort,
                addonCapabilitySchemas = effectiveAddonHost.capabilitySchemas,
                addonRequestPort = addonPort,
                addonLifecyclePort = addonPort,
                initialRedstoneOutput = initialRedstoneOutput,
            )
        return attach(endpoint, host, port, soundPort, addonPort)
    }

    internal fun attach(
        endpoint: VmActorEndpoint,
        host: ProgramRuntimeHost,
        port: ActorRedstoneHostPort? = null,
        soundPort: ActorSoundHostPort? = null,
        addonPort: ActorAddonRequestPort? = null,
    ): ProgramRuntimeActorLease? {
        val processor = ProgramRuntimeActorProcessor(host, port, soundPort, addonPort)
        if (!scheduler.register(endpoint, processor)) return null
        return ProgramRuntimeActorLease(endpoint, processor.closed) { unregister(endpoint) }
    }

    fun request(
        endpoint: VmActorEndpoint,
        command: (ProgramRuntimeRequestId) -> ProgramRuntimeActorCommand,
    ): CompletableFuture<ProgramRuntimeActorReply> {
        val requestId = ProgramRuntimeRequestId(nextRequestId.updateAndGet(::incrementRequestId))
        val preparedCommand = command(requestId)
        require(preparedCommand.requestId == requestId) { "runtime command factory returned a mismatched request id" }
        val address = RequestAddress(endpoint, requestId)
        val future = CompletableFuture<ProgramRuntimeActorReply>()
        val pendingRequest = PendingRequest(future, completesWorldRequest = false)
        check(pending.putIfAbsent(address, pendingRequest) == null) { "runtime request id collision" }
        val submission = scheduler.submit(endpoint, preparedCommand)
        if (submission != VmActorSubmission.ACCEPTED) {
            pending.remove(address, pendingRequest)
            if (preparedCommand.isInput()) rejectedInputRequests.incrementAndGet()
            future.completeExceptionally(ProgramRuntimeActorRequestException(submission))
        } else {
            if (preparedCommand.isInput()) knownRunnable[endpoint] = true
            future.whenComplete { _, _ ->
                if (future.isCancelled) pending.remove(address, pendingRequest)
            }
        }
        return future
    }

    /** Atomically admits the world effects observed for [worldTick] and one bounded VM turn behind them. */
    fun turn(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect> = emptyList(),
    ): CompletableFuture<ProgramRuntimeActorReply> {
        val requestId = ProgramRuntimeRequestId(nextRequestId.updateAndGet(::incrementRequestId))
        val address = RequestAddress(endpoint, requestId)
        val future = CompletableFuture<ProgramRuntimeActorReply>()
        val pendingRequest =
            PendingRequest(
                future,
                effects.any {
                    it is ProgramRuntimeActorEffect.CompleteRedstoneOutput ||
                        it is ProgramRuntimeActorEffect.CompleteSound ||
                        it is ProgramRuntimeActorEffect.CompleteAddons
                },
            )
        check(pending.putIfAbsent(address, pendingRequest) == null) { "runtime request id collision" }
        val permit = ProgramRuntimeTickPermit(requestId, worldTick)
        val submission =
            synchronized(frameLock) {
                val frame = capacityFrame
                if (frame == null && !capacityFramesRequired && latestCapacityTick < 0) {
                    scheduler.submitWithPermit(endpoint, effects, permit)
                } else {
                    scheduler.submitWithDeferredPermit(endpoint, effects, permit).also { accepted ->
                        if (accepted == VmActorSubmission.ACCEPTED) {
                            val turn = PendingTurn(endpoint, permit, address, pendingRequest, effects.isNotEmpty())
                            if (frame == null) awaitingCapacity += turn else frame.turns += turn
                        }
                    }
                }
            }
        if (submission != VmActorSubmission.ACCEPTED) {
            pending.remove(address, pendingRequest)
            if (effects.any { it is ProgramRuntimeActorEffect.RedstoneInput }) rejectedInputRequests.incrementAndGet()
            future.completeExceptionally(ProgramRuntimeActorRequestException(submission))
        } else {
            future.whenComplete { _, _ ->
                if (future.isCancelled) pending.remove(address, pendingRequest)
            }
        }
        return future
    }

    /** Reuses a matching frame grant; null leaves the caller's exact completion available for a later tick. */
    fun continueTurn(
        endpoint: VmActorEndpoint,
        worldTick: Long,
        effects: List<ProgramRuntimeActorEffect>,
    ): CompletableFuture<ProgramRuntimeActorReply>? =
        synchronized(frameLock) {
            val credit = frameCredits[endpoint] ?: return@synchronized null
            if (closed.get() || credit.worldTick != worldTick || credit.inFlight || credit.remaining <= 0 ||
                credit.continuations >= MAXIMUM_CONTINUATIONS_PER_FRAME ||
                (credit.deadlineNanos != Long.MAX_VALUE && nanoTime() - credit.deadlineNanos >= 0) ||
                effects.none(::completesWorldRequest)
            ) {
                return@synchronized null
            }
            val requestId = ProgramRuntimeRequestId(nextRequestId.updateAndGet(::incrementRequestId))
            val address = RequestAddress(endpoint, requestId)
            val future = CompletableFuture<ProgramRuntimeActorReply>()
            val request = PendingRequest(future, completesWorldRequest = true)
            check(pending.putIfAbsent(address, request) == null) { "runtime request id collision" }
            val allowance = credit.remaining.toInt()
            credit.inFlight = true
            reservations[address] = ReservedTurn(allowance.toLong(), credit)
            val submission =
                scheduler.submitWithPermit(
                    endpoint,
                    effects,
                    ProgramRuntimeTickPermit(requestId, worldTick, allowance, credit.deadlineNanos),
                )
            if (submission != VmActorSubmission.ACCEPTED) {
                reservations.remove(address)
                credit.inFlight = false
                pending.remove(address, request)
                return@synchronized null
            }
            credit.continuations++
            future.whenComplete { _, _ -> if (future.isCancelled) pending.remove(address, request) }
            future
        }

    private fun completesWorldRequest(effect: ProgramRuntimeActorEffect): Boolean =
        when (effect) {
            is ProgramRuntimeActorEffect.CompleteAddons -> effect.completions.isNotEmpty()
            is ProgramRuntimeActorEffect.CompleteRedstoneOutput, is ProgramRuntimeActorEffect.CompleteSound -> true
            else -> false
        }

    /** Starts collection for the server tick; carrier turns retain their mailbox fences until flush. */
    fun beginCapacityFrame(worldTick: Long) {
        require(worldTick >= 0) { "world tick must not be negative" }
        synchronized(frameLock) {
            check(capacityFrame == null) { "previous capacity frame has not been flushed" }
            require(worldTick > latestCapacityTick) { "capacity frame must advance the world tick" }
            frameCredits.values.forEach { credit ->
                if (!credit.inFlight) addSaturating(totalUnusedReservations, credit.remaining)
            }
            frameCredits.clear()
            latestCapacityTick = worldTick
            capacityFrame =
                CapacityFrame(
                    worldTick,
                    awaitingCapacity.mapTo(ArrayList()) {
                        it.copy(permit = it.permit.copy(worldTick = worldTick))
                    },
                )
            awaitingCapacity.clear()
        }
    }

    /** Releases only the computers that submitted a turn, with reservations from one shared capacity. */
    fun flushCapacityFrame() {
        synchronized(frameLock) {
            val frame = capacityFrame ?: return
            capacityFrame = null
            val allocation =
                PerComputerCapacityAllocator.allocate(
                    frame.turns.filter { knownRunnable[it.endpoint] != false || it.wakesGuest }.map(PendingTurn::endpoint),
                    perComputerEntitlement,
                    safeInstructionCapacity ?: capacityGovernor.currentCapacity,
                    frame.worldTick,
                )
            val grants = allocation.grants.associateBy(PerComputerCapacityGrant::endpoint)
            lastCapacityAllocation = allocation
            previousFrameHadDemand = allocation.grants.isNotEmpty()
            lastRunnableComputers.set(allocation.grants.size)
            lastWaitingComputers.set(frame.turns.size - allocation.grants.size)
            lastThrottledComputers.set(allocation.grants.count { it.retiredInstructionLimit < perComputerEntitlement })
            val deadlineNanos = capacityDeadlineNanos()
            frame.turns.forEach { turn ->
                val allowance = grants[turn.endpoint]?.retiredInstructionLimit?.toInt() ?: 0
                val credit = FrameCredit(frame.worldTick, allowance.toLong(), deadlineNanos, inFlight = allowance > 0)
                if (allowance > 0) {
                    check(frameCredits.put(turn.endpoint, credit) == null) { "computer received duplicate frame grants" }
                    reservations[turn.address] = ReservedTurn(allowance.toLong(), credit)
                }
                if (!scheduler.releaseDeferredPermit(
                        turn.endpoint,
                        turn.permit.copy(retirementAllowance = allowance, deadlineNanos = deadlineNanos),
                    )
                ) {
                    reservations.remove(turn.address)?.let { addSaturating(totalUnusedReservations, it.allowance) }
                    frameCredits.remove(turn.endpoint)
                    pending.remove(turn.address, turn.request)
                    turn.request.future.completeExceptionally(ProgramRuntimeActorRequestException(VmActorSubmission.STALE_ENDPOINT))
                }
            }
        }
    }

    /** Called once at the next server tick boundary, after draining the prior frame's results. */
    fun observePreviousCapacityFrame() {
        capacityGovernor.observeTick(
            hadDemand = previousFrameHadDemand,
            deadlineOverrun = observedLateTurn,
        )
        observedLateTurn = false
        previousFrameHadDemand = false
    }

    fun unregister(endpoint: VmActorEndpoint): CompletableFuture<Boolean> {
        knownRunnable.remove(endpoint)
        val deferred =
            synchronized(frameLock) {
                frameCredits.remove(endpoint)?.let { credit ->
                    if (!credit.inFlight) addSaturating(totalUnusedReservations, credit.remaining)
                }
                (
                    capacityFrame?.turns?.firstOrNull { it.endpoint == endpoint }
                        ?: awaitingCapacity.firstOrNull { it.endpoint == endpoint }
                )?.also { turn ->
                    capacityFrame?.turns?.remove(turn)
                    awaitingCapacity.remove(turn)
                }
            }
        if (deferred != null) {
            // A closing computer may drain its queued effects, but receives no Guest execution.
            if (!scheduler.releaseDeferredPermit(endpoint, deferred.permit.copy(retirementAllowance = 0))) {
                pending.remove(deferred.address, deferred.request)
                deferred.request.future.completeExceptionally(
                    ProgramRuntimeActorRequestException(VmActorSubmission.STALE_ENDPOINT),
                )
            }
        }
        return scheduler.unregister(endpoint).whenComplete { _, _ -> deferredWorldRequests.remove(endpoint) }
    }

    fun pump(
        maximumEvents: Int,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): Int {
        require(maximumEvents >= 0) { "result delivery allowance must not be negative" }
        val startedAt = nanoTime()
        var count = 0
        while (count < maximumEvents && (deadlineNanos == Long.MAX_VALUE || nanoTime() - deadlineNanos < 0)) {
            val event = scheduler.drainEvents(1).singleOrNull() ?: break
            count++
            when (event) {
                is VmActorEvent.Result -> {
                    val reply = event.value
                    if (reply.hostDeadlineMissed) observedLateTurn = true
                    knownRunnable[event.endpoint] = reply.state == ProgramRuntimeState.Running
                    val address = RequestAddress(event.endpoint, reply.requestId)
                    val reserved = reservations.remove(address)
                    if (reserved != null) {
                        check(reply.retiredInstructions in 0..reserved.allowance) { "actor exceeded its instruction reservation" }
                        addSaturating(totalRetiredInstructions, reply.retiredInstructions)
                        synchronized(frameLock) {
                            val credit = reserved.credit
                            credit.inFlight = false
                            credit.remaining -= reply.retiredInstructions
                            if (frameCredits[event.endpoint] !== credit) {
                                addSaturating(totalUnusedReservations, credit.remaining)
                                credit.remaining = 0
                            }
                        }
                    }
                    val request = pending.remove(address) ?: continue
                    if (request.completesWorldRequest) deferredWorldRequests.remove(event.endpoint)
                    if (
                        (
                            reply.value is ProgramRuntimeActorValue.RedstoneOutputRequested ||
                                reply.value is ProgramRuntimeActorValue.SoundRequested ||
                                reply.addonActions.isNotEmpty()
                        ) &&
                        deferredWorldRequests.add(event.endpoint)
                    ) {
                        totalDeferredWorldRequests.incrementAndGet()
                    }
                    request.future.complete(reply)
                }

                is VmActorEvent.Failed -> {
                    val failure = ProgramRuntimeActorFailedException(event.endpoint, event.cause)
                    deferredWorldRequests.remove(event.endpoint)
                    knownRunnable.remove(event.endpoint)
                    reservations.entries.removeIf { (address, reserved) ->
                        if (address.endpoint != event.endpoint) return@removeIf false
                        synchronized(frameLock) {
                            addSaturating(totalUnusedReservations, reserved.credit.remaining)
                            reserved.credit.remaining = 0
                            frameCredits.remove(event.endpoint)
                        }
                        true
                    }
                    pending.entries.removeIf { (address, request) ->
                        if (address.endpoint != event.endpoint) return@removeIf false
                        request.future.completeExceptionally(failure)
                        true
                    }
                }
            }
        }
        lastPumpEvents.set(count)
        lastPumpNanos.set((nanoTime() - startedAt).coerceAtLeast(0))
        return count
    }

    fun metrics(): VmActorSchedulerMetrics = scheduler.metrics()

    fun calibratedCapacity(): Long = capacityGovernor.calibratedCapacity

    fun currentSafeCapacity(): Long = safeInstructionCapacity ?: capacityGovernor.currentCapacity

    fun capacityFallbackReason(): String? = capacityGovernor.fallbackReason

    fun acceptCapacityCalibration(measurement: VmCapacityCalibration) {
        capacityGovernor.calibrated(measurement)
    }

    fun rejectCapacityCalibration(reason: String) {
        capacityGovernor.calibrationFailed(reason)
    }

    internal fun recordHostContinuationDelay(delayTicks: Long) {
        require(delayTicks >= 0) { "host continuation delay must not be negative" }
        hostContinuationSamples.incrementAndGet()
        totalHostContinuationDelayTicks.addAndGet(delayTicks)
        maximumHostContinuationDelayTicks.accumulateAndGet(delayTicks, ::maxOf)
    }

    internal fun recordCoalescedRedstoneInput() {
        coalescedRedstoneInputs.incrementAndGet()
    }

    fun runtimeMetrics(): ProgramRuntimeActorMetrics =
        ProgramRuntimeActorMetrics(
            scheduler = scheduler.metrics(),
            pendingRequests = pending.size,
            deferredWorldRequests = deferredWorldRequests.size,
            totalDeferredWorldRequests = totalDeferredWorldRequests.get(),
            hostContinuationSamples = hostContinuationSamples.get(),
            totalHostContinuationDelayTicks = totalHostContinuationDelayTicks.get(),
            maximumHostContinuationDelayTicks = maximumHostContinuationDelayTicks.get(),
            rejectedInputRequests = rejectedInputRequests.get(),
            coalescedRedstoneInputs = coalescedRedstoneInputs.get(),
            lastPumpEvents = lastPumpEvents.get(),
            lastPumpNanos = lastPumpNanos.get(),
            calibratedInstructionCapacity = capacityGovernor.calibratedCapacity,
            currentInstructionCapacity = currentSafeCapacity(),
            measuredCapacityWorkers = capacityGovernor.measuredWorkers,
            capacityFallbackReason = capacityGovernor.fallbackReason,
            runnableComputersLastFrame = lastRunnableComputers.get(),
            waitingComputersLastFrame = lastWaitingComputers.get(),
            throttledComputersLastFrame = lastThrottledComputers.get(),
            requestedInstructionsLastFrame = lastCapacityAllocation.requestedInstructions,
            reservedInstructionsLastFrame = lastCapacityAllocation.reservedInstructions,
            missedInstructionsLastFrame = lastCapacityAllocation.missedInstructions,
            retiredInstructionsTotal = totalRetiredInstructions.get(),
            unusedReservationsTotal = totalUnusedReservations.get(),
            countersSaturated = countersSaturated.get(),
        )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(frameLock) {
            (capacityFrame?.turns.orEmpty() + awaitingCapacity).forEach { turn ->
                scheduler.releaseDeferredPermit(turn.endpoint, turn.permit.copy(retirementAllowance = 0))
            }
            capacityFrame = null
            awaitingCapacity.clear()
            frameCredits.values.filterNot { it.inFlight }.forEach { addSaturating(totalUnusedReservations, it.remaining) }
            frameCredits.clear()
        }
        scheduler.close()
        val failure = ProgramRuntimeActorServiceClosedException()
        pending.values.forEach { it.future.completeExceptionally(failure) }
        pending.clear()
        reservations.clear()
        deferredWorldRequests.clear()
        knownRunnable.clear()
    }

    private data class FrameCredit(
        val worldTick: Long,
        var remaining: Long,
        val deadlineNanos: Long,
        var inFlight: Boolean,
        var continuations: Int = 0,
    )

    private data class ReservedTurn(
        val allowance: Long,
        val credit: FrameCredit,
    )

    private data class RequestAddress(
        val endpoint: VmActorEndpoint,
        val requestId: ProgramRuntimeRequestId,
    )

    private data class PendingRequest(
        val future: CompletableFuture<ProgramRuntimeActorReply>,
        val completesWorldRequest: Boolean,
    )

    private data class PendingTurn(
        val endpoint: VmActorEndpoint,
        val permit: ProgramRuntimeTickPermit,
        val address: RequestAddress,
        val request: PendingRequest,
        val wakesGuest: Boolean,
    )

    private data class CapacityFrame(
        val worldTick: Long,
        val turns: MutableList<PendingTurn> = ArrayList(),
    )

    private fun addSaturating(
        counter: AtomicLong,
        value: Long,
    ) {
        require(value >= 0) { "capacity counter increment must not be negative" }
        counter.updateAndGet { previous ->
            if (Long.MAX_VALUE - previous < value) {
                countersSaturated.set(true)
                Long.MAX_VALUE
            } else {
                previous + value
            }
        }
    }

    private fun capacityDeadlineNanos(): Long {
        if (safeInstructionCapacity != null) return Long.MAX_VALUE
        return nanoTime() + capacityGovernor.hostTimeBudgetNanos
    }

    private companion object {
        const val MAXIMUM_CONTINUATIONS_PER_FRAME = 16

        fun incrementRequestId(previous: Long): Long = if (previous == Long.MAX_VALUE) 1 else previous + 1

        fun ProgramRuntimeActorCommand.isInput(): Boolean =
            this is ProgramRuntimeActorCommand.SendTerminalKey ||
                this is ProgramRuntimeActorCommand.SendTerminalText ||
                this is ProgramRuntimeActorCommand.SubmitCanonicalLine
    }
}

class ProgramRuntimeActorRequestException(
    val submission: VmActorSubmission,
) : IllegalStateException("runtime actor request was not accepted: $submission")

class ProgramRuntimeActorFailedException(
    val endpoint: VmActorEndpoint,
    cause: Throwable,
) : IllegalStateException("runtime actor failed: $endpoint", cause)

class ProgramRuntimeActorServiceClosedException : IllegalStateException("runtime actor service is closed")

internal class ActorRedstoneHostPort : RedstoneHostPort {
    private var requestedOutput: Int? = null

    override fun commitOutput(packed: Int): RedstoneCommitResult {
        check(requestedOutput == null) { "redstone actor already owns a deferred output request" }
        requestedOutput = packed
        return RedstoneCommitResult.Deferred
    }

    fun takeRequestedOutput(): Int? = requestedOutput.also { requestedOutput = null }
}

internal class ActorSoundHostPort : SoundHostPort {
    private var requestedSounds: List<SoundRequest>? = null

    override fun emit(requests: List<SoundRequest>): SoundCommitResult {
        check(requestedSounds == null) { "sound actor already owns a deferred request batch" }
        requestedSounds = requests.toList()
        return SoundCommitResult.Deferred
    }

    fun takeRequestedSounds(): List<SoundRequest>? = requestedSounds.also { requestedSounds = null }
}

internal class ActorAddonRequestPort :
    ProgramAddonRequestPort,
    ProgramAddonLifecyclePort {
    private val actions = mutableListOf<ProgramAddonAction>()

    override fun submit(request: ProgramAddonRequest): Boolean =
        submit(
            ProgramAddonAction
                .Request(request),
        )

    override fun submit(action: ProgramAddonAction): Boolean {
        if (actions.size >= MAXIMUM_ADDON_ACTION_BATCH) return false
        actions += action
        return true
    }

    fun takeActions(): List<ProgramAddonAction> = actions.toList().also { actions.clear() }

    fun clear() = actions.clear()

    private companion object {
        const val MAXIMUM_ADDON_ACTION_BATCH = 512
    }
}
