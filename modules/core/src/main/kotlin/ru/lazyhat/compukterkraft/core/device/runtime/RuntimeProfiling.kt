/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.lang.runtime.VmInstructionKind
import ru.lazyhat.compukterkraft.lang.runtime.VmSignalKind
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface RuntimeMetricsCollector {
    fun recordServerTick(nanos: Long)

    fun recordRequestSlice(nanos: Long)

    fun recordHostCallDrain(
        callCount: Int,
        nanos: Long,
    )

    fun recordHostCallDispatch(
        callCount: Int,
        nanos: Long,
    )

    fun recordHostResultDelivery(
        resultCount: Int,
        nanos: Long,
    )

    fun recordDisplayFrameDrain(
        frameCount: Int,
        nanos: Long,
    )

    fun recordDisplayFlush(
        frameCount: Int,
        nanos: Long,
    )

    fun recordSliceRequest(
        sent: Boolean,
        sleepGated: Boolean,
    )

    fun recordExecutionQuotaRefill(
        accepted: Boolean,
        unavailable: Boolean,
    )

    fun recordExecutionQuotaPermitConsumed()

    fun recordProcessSchedulerTick(
        wokenProcesses: Int,
        selected: Boolean,
    )

    fun recordNativeProcessSchedulerComparison(matched: Boolean)

    fun recordSlicePermitReceived()

    fun recordSchedulingPoint(waitedForSlice: Boolean)

    fun recordVmExecutionWindow(nanos: Long)

    fun recordVmSignal(kind: VmSignalKind)

    fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    )

    fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    )

    fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    )

    fun recordNativeWait(
        kind: String,
        nanos: Long,
        woke: Boolean = true,
    )

    fun recordNativeDisplayPumpWait(
        nanos: Long,
        woke: Boolean = true,
    )

    fun recordNativeDisplayFrameBytes(bytes: Int)

    fun recordNativeProcessRegistration()

    fun recordNativeProcessCompletion()

    fun recordNativeProcessStaleCompletion()

    fun snapshot(): RuntimeProfilingSnapshot
}

data class RuntimeTickMetrics(
    val serverTickCalls: Long = 0,
    val serverTickNanos: Long = 0,
    val requestSliceCalls: Long = 0,
    val requestSliceNanos: Long = 0,
    val hostCallDrainCalls: Long = 0,
    val hostCallsDrained: Long = 0,
    val hostCallDrainNanos: Long = 0,
    val hostCallDispatchCalls: Long = 0,
    val hostCallsDispatched: Long = 0,
    val hostCallDispatchNanos: Long = 0,
    val hostResultDeliveryCalls: Long = 0,
    val hostResultsDelivered: Long = 0,
    val hostResultDeliveryNanos: Long = 0,
    val displayFrameDrainCalls: Long = 0,
    val displayFramesDrained: Long = 0,
    val displayFrameDrainNanos: Long = 0,
    val displayFlushCalls: Long = 0,
    val displayFramesFlushed: Long = 0,
    val displayFlushNanos: Long = 0,
) {
    val allCalls =
        serverTickCalls +
            requestSliceCalls +
            hostCallDrainCalls +
            hostCallDispatchCalls +
            hostResultDeliveryCalls +
            displayFrameDrainCalls +
            displayFlushCalls
    val allNanos =
        serverTickNanos +
            requestSliceNanos +
            hostCallDrainNanos +
            hostCallDispatchNanos +
            hostResultDeliveryNanos +
            displayFrameDrainNanos +
            displayFlushNanos
    val tickCalls = serverTickCalls + requestSliceCalls
    val tickNanos = serverTickNanos + requestSliceNanos
    val hostCalls = hostCallDrainCalls + hostCallDispatchCalls + hostResultDeliveryCalls
    val hostNanos = hostCallDrainCalls + hostCallDispatchCalls + hostResultDeliveryCalls
    val displayCalls = displayFrameDrainCalls + displayFlushCalls
    val displayNanos = displayFrameDrainNanos + displayFlushNanos
}

data class RuntimeVmMetrics(
    val sliceRequests: Long = 0,
    val slicePermitsSent: Long = 0,
    val sleepGatedSliceRequests: Long = 0,
    val executionQuotaRefills: Long = 0,
    val executionQuotaAcceptedRefills: Long = 0,
    val executionQuotaUnavailableRefills: Long = 0,
    val executionQuotaPermitsConsumed: Long = 0,
    val processSchedulerTicks: Long = 0,
    val processSchedulerSelectedTicks: Long = 0,
    val processSchedulerIdleTicks: Long = 0,
    val processSchedulerWokenProcesses: Long = 0,
    val nativeProcessSchedulerComparisons: Long = 0,
    val nativeProcessSchedulerMatches: Long = 0,
    val nativeProcessSchedulerMismatches: Long = 0,
    val slicePermitsReceived: Long = 0,
    val schedulingPoints: Long = 0,
    val yieldSchedulingPoints: Long = 0,
    val waitForSliceSchedulingPoints: Long = 0,
    val executionWindows: Long = 0,
    val executionWindowNanos: Long = 0,
    val haltSignals: Long = 0,
    val pauseSignals: Long = 0,
    val yieldSignals: Long = 0,
    val sleepSignals: Long = 0,
    val waitEventSignals: Long = 0,
    val waitPollSignals: Long = 0,
    val waitProcessSignals: Long = 0,
    val hostCallSignals: Long = 0,
    val nativeFastPathCalls: Long = 0,
    val nativeWaitCalls: Long = 0,
    val nativeWaitNanos: Long = 0,
    val nativeWaitWakeups: Long = 0,
    val nativeWaitTimeouts: Long = 0,
    val nativeDisplayPumpWaitCalls: Long = 0,
    val nativeDisplayPumpWaitNanos: Long = 0,
    val nativeDisplayPumpWakeups: Long = 0,
    val nativeDisplayPumpTimeouts: Long = 0,
    val nativeDisplayFrameByteBatches: Long = 0,
    val nativeDisplayFrameBytes: Long = 0,
    val nativeProcessRegistrations: Long = 0,
    val nativeProcessCompletions: Long = 0,
    val nativeProcessStaleCompletions: Long = 0,
) {
    val averageExecutionWindowNanos: Long get() = if (executionWindows <= 0) 0 else executionWindowNanos / executionWindows
    val nativeWaitSignals: Long get() = waitPollSignals
}

data class RuntimeHostCallMetrics(
    val moduleName: String,
    val functionName: String,
    val calls: Long,
    val nanos: Long,
    val waitNanos: Long = 0,
) {
    val averageNanos: Long get() = if (calls <= 0) 0 else nanos / calls
    val activeNanos: Long get() = (nanos - waitNanos).coerceAtLeast(0)
    val averageActiveNanos: Long get() = if (calls <= 0) 0 else activeNanos / calls
}

data class RuntimeInstructionMetrics(
    val kind: VmInstructionKind,
    val count: Long,
    val nanos: Long,
) {
    val averageNanos: Long get() = if (count <= 0) 0 else nanos / count
}

data class RuntimeProfilingSnapshot(
    val tick: RuntimeTickMetrics = RuntimeTickMetrics(),
    val vm: RuntimeVmMetrics = RuntimeVmMetrics(),
    val hostCalls: List<RuntimeHostCallMetrics> = emptyList(),
    val instructions: List<RuntimeInstructionMetrics> = emptyList(),
) {
    fun summary(): String =
        buildString {
            appendLine("runtime:")
            appendLine("  allTicks: calls=${tick.allCalls}, time=${tick.allNanos.nanos()}")
            appendLine("  tick: calls=${tick.tickCalls}, time=${tick.tickNanos.nanos()}")
            appendLine("    server: calls=${tick.serverTickCalls}, time=${tick.serverTickNanos.nanos()}")
            appendLine("    requestSlice: calls=${tick.requestSliceCalls}, time=${tick.requestSliceNanos.nanos()}")
            appendLine("  host-queue: calls=${tick.hostCalls}, time=${tick.hostNanos.nanos()}")
            appendLine(
                "    drain: calls=${tick.hostCallDrainCalls}, items=${tick.hostCallsDrained}, time=${tick.hostCallDrainNanos.nanos()}",
            )
            appendLine(
                "    dispatch: calls=${tick.hostCallDispatchCalls}, items=${tick.hostCallsDispatched}, time=${tick.hostCallDispatchNanos.nanos()}",
            )
            appendLine(
                "    delivery: calls=${tick.hostResultDeliveryCalls}, items=${tick.hostResultsDelivered}, time=${tick.hostResultDeliveryNanos.nanos()}",
            )
            appendLine("  display-runtime: calls=${tick.displayCalls}, time=${tick.displayNanos.nanos()}")
            appendLine(
                "    drain: calls=${tick.displayFrameDrainCalls}, frames=${tick.displayFramesDrained}, time=${tick.displayFrameDrainNanos.nanos()}",
            )
            appendLine(
                "    flush: calls=${tick.displayFlushCalls}, frames=${tick.displayFramesFlushed}, time=${tick.displayFlushNanos.nanos()}",
            )
            appendLine("  vm:")
            appendLine(
                "    slices: requests=${vm.sliceRequests}, permitsSent=${vm.slicePermitsSent}, sleepGated=${vm.sleepGatedSliceRequests}, permitsReceived=${vm.slicePermitsReceived}",
            )
            appendLine(
                "    quota: refills=${vm.executionQuotaRefills}, accepted=${vm.executionQuotaAcceptedRefills}, unavailable=${vm.executionQuotaUnavailableRefills}, consumed=${vm.executionQuotaPermitsConsumed}",
            )
            appendLine(
                "    processScheduler: ticks=${vm.processSchedulerTicks}, selected=${vm.processSchedulerSelectedTicks}, idle=${vm.processSchedulerIdleTicks}, woken=${vm.processSchedulerWokenProcesses}",
            )
            appendLine(
                "    nativeProcessScheduler: comparisons=${vm.nativeProcessSchedulerComparisons}, matches=${vm.nativeProcessSchedulerMatches}, mismatches=${vm.nativeProcessSchedulerMismatches}",
            )
            appendLine(
                "    scheduling: points=${vm.schedulingPoints}, yieldPoints=${vm.yieldSchedulingPoints}, waitPoints=${vm.waitForSliceSchedulingPoints}",
            )
            appendLine(
                "    execution: windows=${vm.executionWindows}, time=${vm.executionWindowNanos.nanos()}, avg=${vm.averageExecutionWindowNanos.nanos()}",
            )
            appendLine(
                "  signals: halt=${vm.haltSignals}, pause=${vm.pauseSignals}, yield=${vm.yieldSignals}, sleep=${vm.sleepSignals}, waitEvent=${vm.waitEventSignals}, waitPoll=${vm.waitPollSignals}, waitProcess=${vm.waitProcessSignals}, hostCall=${vm.hostCallSignals}",
            )
            appendLine(
                "    nativeDisplayPump: waits=${vm.nativeDisplayPumpWaitCalls}, waitTime=${vm.nativeDisplayPumpWaitNanos.nanos()}, wakeups=${vm.nativeDisplayPumpWakeups}, timeouts=${vm.nativeDisplayPumpTimeouts}, byteBatches=${vm.nativeDisplayFrameByteBatches}, bytes=${vm.nativeDisplayFrameBytes}",
            )
            appendLine(
                "  process: registrations=${vm.nativeProcessRegistrations}, completions=${vm.nativeProcessCompletions}, staleCompletions=${vm.nativeProcessStaleCompletions}",
            )
            appendHostCallSummary()
            appendInstructionSummary()
        }

    private fun StringBuilder.appendHostCallSummary() {
        if (hostCalls.isEmpty()) {
            appendLine("  host-calls: none")
            return
        }
        appendLine(
            "  host-calls: calls=${
                hostCalls.sumOf {
                    it.calls
                }
            }, total=${hostCalls.sumOf { it.nanos }.nanos()}, wait=${
                hostCalls.sumOf { it.waitNanos }.nanos()
            }, active=${hostCalls.sumOf { it.activeNanos }.nanos()}",
        )
        hostCalls.sortedBy { it.moduleName + it.functionName }.forEach { call ->
            appendLine(
                "    ${call.moduleName}.${call.functionName}: count=${call.calls}, total=${call.nanos.nanos()}, wait=${call.waitNanos.nanos()}, active=${call.activeNanos.nanos()}, avgActive=${call.averageActiveNanos.nanos()}",
            )
        }
    }

    private fun StringBuilder.appendInstructionSummary() {
        if (instructions.isEmpty()) {
            append("  instructions: none")
            return
        }
        appendLine(
            "  instructions: count=${
                instructions.sumOf {
                    it.count
                }
            }, time=${instructions.sumOf { it.nanos }.nanos()}, avg=${instructions.sumOf { it.averageNanos }.nanos()}",
        )
        instructions.sortedBy { it.kind }.forEachIndexed { index, instruction ->
            val line =
                "    ${instruction.kind}: count=${instruction.count}," +
                    " time=${instruction.nanos.nanos()}," +
                    " avg=${instruction.averageNanos.nanos()}"
            if (index == instructions.lastIndex) append(line) else appendLine(line)
        }
    }
}

private fun Long.nanos(): String = "$this ns"

object NoOpRuntimeMetricsCollector : RuntimeMetricsCollector {
    override fun recordServerTick(nanos: Long) = Unit

    override fun recordRequestSlice(nanos: Long) = Unit

    override fun recordHostCallDrain(
        callCount: Int,
        nanos: Long,
    ) = Unit

    override fun recordHostCallDispatch(
        callCount: Int,
        nanos: Long,
    ) = Unit

    override fun recordHostResultDelivery(
        resultCount: Int,
        nanos: Long,
    ) = Unit

    override fun recordDisplayFrameDrain(
        frameCount: Int,
        nanos: Long,
    ) = Unit

    override fun recordDisplayFlush(
        frameCount: Int,
        nanos: Long,
    ) = Unit

    override fun recordSliceRequest(
        sent: Boolean,
        sleepGated: Boolean,
    ) = Unit

    override fun recordExecutionQuotaRefill(
        accepted: Boolean,
        unavailable: Boolean,
    ) = Unit

    override fun recordExecutionQuotaPermitConsumed() = Unit

    override fun recordProcessSchedulerTick(
        wokenProcesses: Int,
        selected: Boolean,
    ) = Unit

    override fun recordNativeProcessSchedulerComparison(matched: Boolean) = Unit

    override fun recordSlicePermitReceived() = Unit

    override fun recordSchedulingPoint(waitedForSlice: Boolean) = Unit

    override fun recordVmExecutionWindow(nanos: Long) = Unit

    override fun recordVmSignal(kind: VmSignalKind) = Unit

    override fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) = Unit

    override fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) = Unit

    override fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    ) = Unit

    override fun recordNativeWait(
        kind: String,
        nanos: Long,
        woke: Boolean,
    ) = Unit

    override fun recordNativeDisplayPumpWait(
        nanos: Long,
        woke: Boolean,
    ) = Unit

    override fun recordNativeDisplayFrameBytes(bytes: Int) = Unit

    override fun recordNativeProcessRegistration() = Unit

    override fun recordNativeProcessCompletion() = Unit

    override fun recordNativeProcessStaleCompletion() = Unit

    override fun snapshot(): RuntimeProfilingSnapshot = RuntimeProfilingSnapshot()
}

private class RuntimeCounter {
    val count = AtomicLong()
    val nanos = AtomicLong()

    fun record(nanos: Long) {
        count.incrementAndGet()
        this.nanos.addAndGet(nanos.coerceAtLeast(0))
    }

    fun recordWait(nanos: Long) {
        waitNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    val waitNanos = AtomicLong()
}

class RecordingRuntimeMetricsCollector : RuntimeMetricsCollector {
    private val serverTickCalls = AtomicLong()
    private val serverTickNanos = AtomicLong()
    private val requestSliceCalls = AtomicLong()
    private val requestSliceNanos = AtomicLong()
    private val hostCallDrainCalls = AtomicLong()
    private val hostCallsDrained = AtomicLong()
    private val hostCallDrainNanos = AtomicLong()
    private val hostCallDispatchCalls = AtomicLong()
    private val hostCallsDispatched = AtomicLong()
    private val hostCallDispatchNanos = AtomicLong()
    private val hostResultDeliveryCalls = AtomicLong()
    private val hostResultsDelivered = AtomicLong()
    private val hostResultDeliveryNanos = AtomicLong()
    private val displayFrameDrainCalls = AtomicLong()
    private val displayFramesDrained = AtomicLong()
    private val displayFrameDrainNanos = AtomicLong()
    private val displayFlushCalls = AtomicLong()
    private val displayFramesFlushed = AtomicLong()
    private val displayFlushNanos = AtomicLong()
    private val sliceRequests = AtomicLong()
    private val slicePermitsSent = AtomicLong()
    private val sleepGatedSliceRequests = AtomicLong()
    private val executionQuotaRefills = AtomicLong()
    private val executionQuotaAcceptedRefills = AtomicLong()
    private val executionQuotaUnavailableRefills = AtomicLong()
    private val executionQuotaPermitsConsumed = AtomicLong()
    private val processSchedulerTicks = AtomicLong()
    private val processSchedulerSelectedTicks = AtomicLong()
    private val processSchedulerIdleTicks = AtomicLong()
    private val processSchedulerWokenProcesses = AtomicLong()
    private val nativeProcessSchedulerComparisons = AtomicLong()
    private val nativeProcessSchedulerMatches = AtomicLong()
    private val nativeProcessSchedulerMismatches = AtomicLong()
    private val slicePermitsReceived = AtomicLong()
    private val schedulingPoints = AtomicLong()
    private val yieldSchedulingPoints = AtomicLong()
    private val waitForSliceSchedulingPoints = AtomicLong()
    private val executionWindows = AtomicLong()
    private val executionWindowNanos = AtomicLong()
    private val haltSignals = AtomicLong()
    private val pauseSignals = AtomicLong()
    private val yieldSignals = AtomicLong()
    private val sleepSignals = AtomicLong()
    private val waitEventSignals = AtomicLong()
    private val waitPollSignals = AtomicLong()
    private val waitProcessSignals = AtomicLong()
    private val hostCallSignals = AtomicLong()
    private val nativeWaitCalls = AtomicLong()
    private val nativeWaitNanos = AtomicLong()
    private val nativeWaitWakeups = AtomicLong()
    private val nativeWaitTimeouts = AtomicLong()
    private val nativeDisplayPumpWaitCalls = AtomicLong()
    private val nativeDisplayPumpWaitNanos = AtomicLong()
    private val nativeDisplayPumpWakeups = AtomicLong()
    private val nativeDisplayPumpTimeouts = AtomicLong()
    private val nativeDisplayFrameByteBatches = AtomicLong()
    private val nativeDisplayFrameBytes = AtomicLong()
    private val nativeProcessRegistrations = AtomicLong()
    private val nativeProcessCompletions = AtomicLong()
    private val nativeProcessStaleCompletions = AtomicLong()
    private val hostCalls = ConcurrentHashMap<Pair<String, String>, RuntimeCounter>()
    private val instructions = ConcurrentHashMap<VmInstructionKind, RuntimeCounter>()

    override fun recordServerTick(nanos: Long) {
        serverTickCalls.incrementAndGet()
        serverTickNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordRequestSlice(nanos: Long) {
        requestSliceCalls.incrementAndGet()
        requestSliceNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordHostCallDrain(
        callCount: Int,
        nanos: Long,
    ) {
        hostCallDrainCalls.incrementAndGet()
        hostCallsDrained.addAndGet(callCount.coerceAtLeast(0).toLong())
        hostCallDrainNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordHostCallDispatch(
        callCount: Int,
        nanos: Long,
    ) {
        hostCallDispatchCalls.incrementAndGet()
        hostCallsDispatched.addAndGet(callCount.coerceAtLeast(0).toLong())
        hostCallDispatchNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordHostResultDelivery(
        resultCount: Int,
        nanos: Long,
    ) {
        hostResultDeliveryCalls.incrementAndGet()
        hostResultsDelivered.addAndGet(resultCount.coerceAtLeast(0).toLong())
        hostResultDeliveryNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordDisplayFrameDrain(
        frameCount: Int,
        nanos: Long,
    ) {
        displayFrameDrainCalls.incrementAndGet()
        displayFramesDrained.addAndGet(frameCount.coerceAtLeast(0).toLong())
        displayFrameDrainNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordDisplayFlush(
        frameCount: Int,
        nanos: Long,
    ) {
        displayFlushCalls.incrementAndGet()
        displayFramesFlushed.addAndGet(frameCount.coerceAtLeast(0).toLong())
        displayFlushNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordSliceRequest(
        sent: Boolean,
        sleepGated: Boolean,
    ) {
        sliceRequests.incrementAndGet()
        if (sent) slicePermitsSent.incrementAndGet()
        if (sleepGated) sleepGatedSliceRequests.incrementAndGet()
    }

    override fun recordExecutionQuotaRefill(
        accepted: Boolean,
        unavailable: Boolean,
    ) {
        executionQuotaRefills.incrementAndGet()
        if (accepted) executionQuotaAcceptedRefills.incrementAndGet()
        if (unavailable) executionQuotaUnavailableRefills.incrementAndGet()
    }

    override fun recordExecutionQuotaPermitConsumed() {
        executionQuotaPermitsConsumed.incrementAndGet()
    }

    override fun recordProcessSchedulerTick(
        wokenProcesses: Int,
        selected: Boolean,
    ) {
        processSchedulerTicks.incrementAndGet()
        processSchedulerWokenProcesses.addAndGet(wokenProcesses.coerceAtLeast(0).toLong())
        if (selected) {
            processSchedulerSelectedTicks.incrementAndGet()
        } else {
            processSchedulerIdleTicks.incrementAndGet()
        }
    }

    override fun recordNativeProcessSchedulerComparison(matched: Boolean) {
        nativeProcessSchedulerComparisons.incrementAndGet()
        if (matched) {
            nativeProcessSchedulerMatches.incrementAndGet()
        } else {
            nativeProcessSchedulerMismatches.incrementAndGet()
        }
    }

    override fun recordSlicePermitReceived() {
        slicePermitsReceived.incrementAndGet()
    }

    override fun recordSchedulingPoint(waitedForSlice: Boolean) {
        schedulingPoints.incrementAndGet()
        if (waitedForSlice) {
            waitForSliceSchedulingPoints.incrementAndGet()
        } else {
            yieldSchedulingPoints.incrementAndGet()
        }
    }

    override fun recordVmExecutionWindow(nanos: Long) {
        executionWindows.incrementAndGet()
        executionWindowNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordVmSignal(kind: VmSignalKind) {
        when (kind) {
            VmSignalKind.HALT -> haltSignals.incrementAndGet()
            VmSignalKind.PAUSE -> pauseSignals.incrementAndGet()
            VmSignalKind.YIELD -> yieldSignals.incrementAndGet()
            VmSignalKind.SLEEP -> sleepSignals.incrementAndGet()
            VmSignalKind.WAIT_EVENT -> waitEventSignals.incrementAndGet()
            VmSignalKind.WAIT_POLL -> waitPollSignals.incrementAndGet()
            VmSignalKind.WAIT_PROCESS -> waitProcessSignals.incrementAndGet()
            VmSignalKind.HOST_CALL -> hostCallSignals.incrementAndGet()
        }
    }

    override fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) {
        hostCalls.computeIfAbsent(moduleName to functionName) { RuntimeCounter() }.record(nanos)
    }

    override fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) {
        hostCalls.computeIfAbsent(moduleName to functionName) { RuntimeCounter() }.recordWait(nanos)
    }

    override fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    ) {
        instructions.computeIfAbsent(kind) { RuntimeCounter() }.record(nanos)
    }

    override fun recordNativeWait(
        kind: String,
        nanos: Long,
        woke: Boolean,
    ) {
        nativeWaitCalls.incrementAndGet()
        nativeWaitNanos.addAndGet(nanos.coerceAtLeast(0))
        if (woke) {
            nativeWaitWakeups.incrementAndGet()
        } else {
            nativeWaitTimeouts.incrementAndGet()
        }
    }

    override fun recordNativeDisplayPumpWait(
        nanos: Long,
        woke: Boolean,
    ) {
        nativeDisplayPumpWaitCalls.incrementAndGet()
        nativeDisplayPumpWaitNanos.addAndGet(nanos.coerceAtLeast(0))
        if (woke) {
            nativeDisplayPumpWakeups.incrementAndGet()
        } else {
            nativeDisplayPumpTimeouts.incrementAndGet()
        }
    }

    override fun recordNativeDisplayFrameBytes(bytes: Int) {
        nativeDisplayFrameByteBatches.incrementAndGet()
        nativeDisplayFrameBytes.addAndGet(bytes.coerceAtLeast(0).toLong())
    }

    override fun recordNativeProcessRegistration() {
        nativeProcessRegistrations.incrementAndGet()
    }

    override fun recordNativeProcessCompletion() {
        nativeProcessCompletions.incrementAndGet()
    }

    override fun recordNativeProcessStaleCompletion() {
        nativeProcessStaleCompletions.incrementAndGet()
    }

    override fun snapshot(): RuntimeProfilingSnapshot =
        RuntimeProfilingSnapshot(
            tick =
                RuntimeTickMetrics(
                    serverTickCalls = serverTickCalls.get(),
                    serverTickNanos = serverTickNanos.get(),
                    requestSliceCalls = requestSliceCalls.get(),
                    requestSliceNanos = requestSliceNanos.get(),
                    hostCallDrainCalls = hostCallDrainCalls.get(),
                    hostCallsDrained = hostCallsDrained.get(),
                    hostCallDrainNanos = hostCallDrainNanos.get(),
                    hostCallDispatchCalls = hostCallDispatchCalls.get(),
                    hostCallsDispatched = hostCallsDispatched.get(),
                    hostCallDispatchNanos = hostCallDispatchNanos.get(),
                    hostResultDeliveryCalls = hostResultDeliveryCalls.get(),
                    hostResultsDelivered = hostResultsDelivered.get(),
                    hostResultDeliveryNanos = hostResultDeliveryNanos.get(),
                    displayFrameDrainCalls = displayFrameDrainCalls.get(),
                    displayFramesDrained = displayFramesDrained.get(),
                    displayFrameDrainNanos = displayFrameDrainNanos.get(),
                    displayFlushCalls = displayFlushCalls.get(),
                    displayFramesFlushed = displayFramesFlushed.get(),
                    displayFlushNanos = displayFlushNanos.get(),
                ),
            vm =
                RuntimeVmMetrics(
                    sliceRequests = sliceRequests.get(),
                    slicePermitsSent = slicePermitsSent.get(),
                    sleepGatedSliceRequests = sleepGatedSliceRequests.get(),
                    executionQuotaRefills = executionQuotaRefills.get(),
                    executionQuotaAcceptedRefills = executionQuotaAcceptedRefills.get(),
                    executionQuotaUnavailableRefills = executionQuotaUnavailableRefills.get(),
                    executionQuotaPermitsConsumed = executionQuotaPermitsConsumed.get(),
                    processSchedulerTicks = processSchedulerTicks.get(),
                    processSchedulerSelectedTicks = processSchedulerSelectedTicks.get(),
                    processSchedulerIdleTicks = processSchedulerIdleTicks.get(),
                    processSchedulerWokenProcesses = processSchedulerWokenProcesses.get(),
                    nativeProcessSchedulerComparisons = nativeProcessSchedulerComparisons.get(),
                    nativeProcessSchedulerMatches = nativeProcessSchedulerMatches.get(),
                    nativeProcessSchedulerMismatches = nativeProcessSchedulerMismatches.get(),
                    slicePermitsReceived = slicePermitsReceived.get(),
                    schedulingPoints = schedulingPoints.get(),
                    yieldSchedulingPoints = yieldSchedulingPoints.get(),
                    waitForSliceSchedulingPoints = waitForSliceSchedulingPoints.get(),
                    executionWindows = executionWindows.get(),
                    executionWindowNanos = executionWindowNanos.get(),
                    haltSignals = haltSignals.get(),
                    pauseSignals = pauseSignals.get(),
                    yieldSignals = yieldSignals.get(),
                    sleepSignals = sleepSignals.get(),
                    waitEventSignals = waitEventSignals.get(),
                    waitPollSignals = waitPollSignals.get(),
                    waitProcessSignals = waitProcessSignals.get(),
                    hostCallSignals = hostCallSignals.get(),
                    nativeWaitCalls = nativeWaitCalls.get(),
                    nativeWaitNanos = nativeWaitNanos.get(),
                    nativeWaitWakeups = nativeWaitWakeups.get(),
                    nativeWaitTimeouts = nativeWaitTimeouts.get(),
                    nativeDisplayPumpWaitCalls = nativeDisplayPumpWaitCalls.get(),
                    nativeDisplayPumpWaitNanos = nativeDisplayPumpWaitNanos.get(),
                    nativeDisplayPumpWakeups = nativeDisplayPumpWakeups.get(),
                    nativeDisplayPumpTimeouts = nativeDisplayPumpTimeouts.get(),
                    nativeDisplayFrameByteBatches = nativeDisplayFrameByteBatches.get(),
                    nativeDisplayFrameBytes = nativeDisplayFrameBytes.get(),
                    nativeProcessRegistrations = nativeProcessRegistrations.get(),
                    nativeProcessCompletions = nativeProcessCompletions.get(),
                    nativeProcessStaleCompletions = nativeProcessStaleCompletions.get(),
                ),
            hostCalls =
                hostCalls
                    .map { (key, counter) ->
                        RuntimeHostCallMetrics(
                            moduleName = key.first,
                            functionName = key.second,
                            calls = counter.count.get(),
                            nanos = counter.nanos.get(),
                            waitNanos = counter.waitNanos.get(),
                        )
                    }.sortedWith(
                        compareByDescending<RuntimeHostCallMetrics> { it.activeNanos }
                            .thenByDescending { it.nanos }
                            .thenBy { it.moduleName }
                            .thenBy { it.functionName },
                    ),
            instructions =
                instructions
                    .map { (kind, counter) ->
                        RuntimeInstructionMetrics(
                            kind = kind,
                            count = counter.count.get(),
                            nanos = counter.nanos.get(),
                        )
                    }.sortedWith(compareByDescending<RuntimeInstructionMetrics> { it.nanos }.thenBy { it.kind.name }),
        )
}
