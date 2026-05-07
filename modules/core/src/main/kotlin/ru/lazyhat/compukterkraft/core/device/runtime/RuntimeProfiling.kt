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

    fun recordSlicePermitReceived()

    fun recordSchedulingPoint(waitedForSlice: Boolean)

    fun recordVmExecutionWindow(nanos: Long)

    fun recordVmSignal(kind: VmSignalKind)

    fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    )

    fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    )

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
)

data class RuntimeVmMetrics(
    val sliceRequests: Long = 0,
    val slicePermitsSent: Long = 0,
    val sleepGatedSliceRequests: Long = 0,
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
    val hostCallSignals: Long = 0,
) {
    val averageExecutionWindowNanos: Long get() = if (executionWindows <= 0) 0 else executionWindowNanos / executionWindows
}

data class RuntimeHostCallMetrics(
    val moduleName: String,
    val functionName: String,
    val calls: Long,
    val nanos: Long,
) {
    val averageNanos: Long get() = if (calls <= 0) 0 else nanos / calls
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
            appendLine("  tick:")
            appendLine("    server: calls=${tick.serverTickCalls}, time=${tick.serverTickNanos.nanos()}")
            appendLine("    requestSlice: calls=${tick.requestSliceCalls}, time=${tick.requestSliceNanos.nanos()}")
            appendLine("  host-queue:")
            appendLine("    drain: calls=${tick.hostCallDrainCalls}, items=${tick.hostCallsDrained}, time=${tick.hostCallDrainNanos.nanos()}")
            appendLine("    dispatch: calls=${tick.hostCallDispatchCalls}, items=${tick.hostCallsDispatched}, time=${tick.hostCallDispatchNanos.nanos()}")
            appendLine("    delivery: calls=${tick.hostResultDeliveryCalls}, items=${tick.hostResultsDelivered}, time=${tick.hostResultDeliveryNanos.nanos()}")
            appendLine("  display-runtime:")
            appendLine("    drain: calls=${tick.displayFrameDrainCalls}, frames=${tick.displayFramesDrained}, time=${tick.displayFrameDrainNanos.nanos()}")
            appendLine("    flush: calls=${tick.displayFlushCalls}, frames=${tick.displayFramesFlushed}, time=${tick.displayFlushNanos.nanos()}")
            appendLine("  vm:")
            appendLine("    slices: requests=${vm.sliceRequests}, permitsSent=${vm.slicePermitsSent}, sleepGated=${vm.sleepGatedSliceRequests}, permitsReceived=${vm.slicePermitsReceived}")
            appendLine("    scheduling: points=${vm.schedulingPoints}, yieldPoints=${vm.yieldSchedulingPoints}, waitPoints=${vm.waitForSliceSchedulingPoints}")
            appendLine("    execution: windows=${vm.executionWindows}, time=${vm.executionWindowNanos.nanos()}, avg=${vm.averageExecutionWindowNanos.nanos()}")
            appendLine("  signals: halt=${vm.haltSignals}, pause=${vm.pauseSignals}, yield=${vm.yieldSignals}, sleep=${vm.sleepSignals}, waitEvent=${vm.waitEventSignals}, hostCall=${vm.hostCallSignals}")
            appendHostCallSummary()
            appendInstructionSummary()
        }

    private fun StringBuilder.appendHostCallSummary() {
        if (hostCalls.isEmpty()) {
            appendLine("  host-calls: none")
            return
        }
        appendLine("  host-calls:")
        hostCalls.forEach { call ->
            appendLine("    ${call.moduleName}.${call.functionName}: count=${call.calls}, time=${call.nanos.nanos()}, avg=${call.averageNanos.nanos()}")
        }
    }

    private fun StringBuilder.appendInstructionSummary() {
        if (instructions.isEmpty()) {
            append("  instructions: none")
            return
        }
        appendLine("  instructions:")
        instructions.forEachIndexed { index, instruction ->
            val line = "    ${instruction.kind}: count=${instruction.count}, time=${instruction.nanos.nanos()}, avg=${instruction.averageNanos.nanos()}"
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

    override fun recordSlicePermitReceived() = Unit

    override fun recordSchedulingPoint(waitedForSlice: Boolean) = Unit

    override fun recordVmExecutionWindow(nanos: Long) = Unit

    override fun recordVmSignal(kind: VmSignalKind) = Unit

    override fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) = Unit

    override fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    ) = Unit

    override fun snapshot(): RuntimeProfilingSnapshot = RuntimeProfilingSnapshot()
}

private class RuntimeCounter {
    val count = AtomicLong()
    val nanos = AtomicLong()

    fun record(nanos: Long) {
        count.incrementAndGet()
        this.nanos.addAndGet(nanos.coerceAtLeast(0))
    }
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
    private val hostCallSignals = AtomicLong()
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

    override fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    ) {
        instructions.computeIfAbsent(kind) { RuntimeCounter() }.record(nanos)
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
                    hostCallSignals = hostCallSignals.get(),
                ),
            hostCalls =
                hostCalls.map { (key, counter) ->
                    RuntimeHostCallMetrics(
                        moduleName = key.first,
                        functionName = key.second,
                        calls = counter.count.get(),
                        nanos = counter.nanos.get(),
                    )
                }.sortedWith(compareByDescending<RuntimeHostCallMetrics> { it.nanos }.thenBy { it.moduleName }.thenBy { it.functionName }),
            instructions =
                instructions.map { (kind, counter) ->
                    RuntimeInstructionMetrics(
                        kind = kind,
                        count = counter.count.get(),
                        nanos = counter.nanos.get(),
                    )
                }.sortedWith(compareByDescending<RuntimeInstructionMetrics> { it.nanos }.thenBy { it.kind.name }),
        )
}
