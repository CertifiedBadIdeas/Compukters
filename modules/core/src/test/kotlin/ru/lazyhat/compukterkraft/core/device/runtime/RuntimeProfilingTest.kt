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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeProfilingTest {
    @Test
    fun recordingCollectorAccumulatesRuntimeAndVmMetrics() {
        val collector = RecordingRuntimeMetricsCollector()

        collector.recordServerTick(nanos = 100)
        collector.recordRequestSlice(nanos = 10)
        collector.recordHostCallDrain(callCount = 2, nanos = 20)
        collector.recordHostCallDispatch(callCount = 2, nanos = 30)
        collector.recordHostResultDelivery(resultCount = 2, nanos = 40)
        collector.recordDisplayFrameDrain(frameCount = 3, nanos = 50)
        collector.recordDisplayFlush(frameCount = 3, nanos = 60)
        collector.recordSliceRequest(sent = true, sleepGated = false)
        collector.recordSliceRequest(sent = false, sleepGated = true)
        collector.recordSlicePermitReceived()
        collector.recordSchedulingPoint(waitedForSlice = false)
        collector.recordSchedulingPoint(waitedForSlice = true)
        collector.recordVmExecutionWindow(nanos = 70)

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.tick.serverTickCalls)
        assertEquals(100, snapshot.tick.serverTickNanos)
        assertEquals(1, snapshot.tick.requestSliceCalls)
        assertEquals(10, snapshot.tick.requestSliceNanos)
        assertEquals(1, snapshot.tick.hostCallDrainCalls)
        assertEquals(2, snapshot.tick.hostCallsDrained)
        assertEquals(20, snapshot.tick.hostCallDrainNanos)
        assertEquals(1, snapshot.tick.hostCallDispatchCalls)
        assertEquals(2, snapshot.tick.hostCallsDispatched)
        assertEquals(30, snapshot.tick.hostCallDispatchNanos)
        assertEquals(1, snapshot.tick.hostResultDeliveryCalls)
        assertEquals(2, snapshot.tick.hostResultsDelivered)
        assertEquals(40, snapshot.tick.hostResultDeliveryNanos)
        assertEquals(1, snapshot.tick.displayFrameDrainCalls)
        assertEquals(3, snapshot.tick.displayFramesDrained)
        assertEquals(50, snapshot.tick.displayFrameDrainNanos)
        assertEquals(1, snapshot.tick.displayFlushCalls)
        assertEquals(3, snapshot.tick.displayFramesFlushed)
        assertEquals(60, snapshot.tick.displayFlushNanos)
        assertEquals(2, snapshot.vm.sliceRequests)
        assertEquals(1, snapshot.vm.slicePermitsSent)
        assertEquals(1, snapshot.vm.sleepGatedSliceRequests)
        assertEquals(1, snapshot.vm.slicePermitsReceived)
        assertEquals(2, snapshot.vm.schedulingPoints)
        assertEquals(1, snapshot.vm.yieldSchedulingPoints)
        assertEquals(1, snapshot.vm.waitForSliceSchedulingPoints)
        assertEquals(1, snapshot.vm.executionWindows)
        assertEquals(70, snapshot.vm.executionWindowNanos)
        assertTrue(snapshot.summary().contains("runtime:"))
        assertTrue(snapshot.summary().contains("vm:"))
    }

    @Test
    fun noopCollectorKeepsEmptySnapshot() {
        val collector = NoOpRuntimeMetricsCollector

        collector.recordServerTick(nanos = 100)
        collector.recordRequestSlice(nanos = 10)
        collector.recordHostCallDrain(callCount = 2, nanos = 20)
        collector.recordHostCallDispatch(callCount = 2, nanos = 30)
        collector.recordHostResultDelivery(resultCount = 2, nanos = 40)
        collector.recordDisplayFrameDrain(frameCount = 3, nanos = 50)
        collector.recordDisplayFlush(frameCount = 3, nanos = 60)
        collector.recordSliceRequest(sent = true, sleepGated = false)
        collector.recordSlicePermitReceived()
        collector.recordSchedulingPoint(waitedForSlice = true)
        collector.recordVmExecutionWindow(nanos = 70)

        assertEquals(RuntimeProfilingSnapshot(), collector.snapshot())
    }
}
