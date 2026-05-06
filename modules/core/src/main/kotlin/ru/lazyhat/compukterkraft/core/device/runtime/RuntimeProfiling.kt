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

import ru.lazyhat.compukterkraft.lang.runtime.VmSignalKind
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

data class RuntimeProfilingSnapshot(
    val tick: RuntimeTickMetrics = RuntimeTickMetrics(),
    val vm: RuntimeVmMetrics = RuntimeVmMetrics(),
) {
    fun summary(): String =
        "runtime: serverTicks=${tick.serverTickCalls}, serverTickNanos=${tick.serverTickNanos}, " +
            "requestSliceCalls=${tick.requestSliceCalls}, requestSliceNanos=${tick.requestSliceNanos}\n" +
            "host: drainedCalls=${tick.hostCallsDrained}, dispatchedCalls=${tick.hostCallsDispatched}, " +
            "deliveredResults=${tick.hostResultsDelivered}, drainNanos=${tick.hostCallDrainNanos}, " +
            "dispatchNanos=${tick.hostCallDispatchNanos}, deliverNanos=${tick.hostResultDeliveryNanos}\n" +
            "display-runtime: drainFrames=${tick.displayFramesDrained}, drainNanos=${tick.displayFrameDrainNanos}, " +
            "flushCalls=${tick.displayFlushCalls}, flushFrames=${tick.displayFramesFlushed}, " +
            "flushNanos=${tick.displayFlushNanos}\n" +
            "vm: sliceRequests=${vm.sliceRequests}, slicePermits=${vm.slicePermitsSent}, " +
            "sleepGated=${vm.sleepGatedSliceRequests}, permitsReceived=${vm.slicePermitsReceived}, " +
            "schedulingPoints=${vm.schedulingPoints}, yieldPoints=${vm.yieldSchedulingPoints}, " +
            "waitPoints=${vm.waitForSliceSchedulingPoints}, executionWindows=${vm.executionWindows}, " +
            "executionNanos=${vm.executionWindowNanos}, avgExecutionWindowNanos=${vm.averageExecutionWindowNanos}\n" +
            "signals: halt=${vm.haltSignals}, pause=${vm.pauseSignals}, yield=${vm.yieldSignals}, " +
            "sleep=${vm.sleepSignals}, waitEvent=${vm.waitEventSignals}, hostCall=${vm.hostCallSignals}"
}

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

    override fun snapshot(): RuntimeProfilingSnapshot = RuntimeProfilingSnapshot()
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
        )
}
