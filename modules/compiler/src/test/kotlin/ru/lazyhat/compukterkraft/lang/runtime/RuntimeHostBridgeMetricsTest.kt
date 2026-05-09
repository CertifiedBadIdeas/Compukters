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

package ru.lazyhat.compukterkraft.lang.runtime

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeHostBridgeMetricsTest {
    @Test
    fun blockingPollAndIpcReadRecordHostCallWaitTime() =
        runBlocking {
            val metrics = RecordingDeviceRuntimeMetrics()
            val runtime = RecordingRuntime(metrics = metrics, queuedEvents = listOf(VmEvent("timer")))
            val bridge = RuntimeHostBridge(runtime)
            val channelId = (bridge.invoke("ipc", "open", emptyList()) as VmValue.IntValue).value
            bridge.invoke("ipc", "write", listOf(VmValue.IntValue(channelId), VmValue.StringValue("abc")))

            val read = bridge.invoke("ipc", "read", listOf(VmValue.IntValue(channelId)))
            val poll = bridge.invoke("runtime", "poll", listOf(VmValue.IntValue(channelId)))

            assertEquals(VmValue.StringValue("abc"), read)
            assertEquals("event", ((poll as VmValue.RecordValue).fields.getValue("kind") as VmValue.StringValue).value)
            assertTrue(metrics.hostCallWaitNanos.getValue("ipc.read") > 0)
            assertTrue(metrics.hostCallWaitNanos.getValue("runtime.poll") > 0)
        }
}
