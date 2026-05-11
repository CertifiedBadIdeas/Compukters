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

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.device.vm.DeviceVmSupervisor
import ru.lazyhat.compukterkraft.core.platform.api.ServerWorldAccess
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceVmHandle
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeDeviceImplDisplayTest {
    @Test
    fun keepsSharedDisplayEndpointAttachedUntilLastSessionDetaches() {
        val tracker = DisplaySessionTracker()
        val firstPlayer = UUID.randomUUID()
        val secondPlayer = UUID.randomUUID()

        assertEquals(DisplayEndpoint(1, 32, 16), tracker.attach(firstPlayer, containerId = 11, displayId = 1, width = 32, height = 16))
        assertEquals(DisplayEndpoint(1, 32, 16), tracker.attach(secondPlayer, containerId = 12, displayId = 1, width = 32, height = 16))
        assertNull(tracker.detach(firstPlayer, displayId = 1))

        assertEquals(listOf(DisplayEndpoint(1, 32, 16)), tracker.activeEndpoints())
        assertEquals(1, tracker.detach(secondPlayer, displayId = 1))
        assertEquals(emptyList(), tracker.activeEndpoints())
    }

    @Test
    fun serviceVmTickRequestsNativeDaemonSliceOnly() {
        val metrics = RecordingRuntimeMetricsCollector()
        val handle = RecordingVmHandle()

        serviceVmTick(
            handle = handle,
            serverTick = 42L,
            runtimeMetricsCollector = metrics,
        )

        assertEquals(1, handle.requestSliceCalls)
        assertEquals(42L, handle.lastServerTick)
        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.tick.requestSliceCalls)
    }

    @Test
    fun recordsServerTickRuntimeMetrics() {
        if (System.getProperty("ckl.vm.native.library")?.isNotBlank() != true) return

        val supervisor = DeviceVmSupervisor(ServerWorldAccess { createTempDirectory("runtime-profiling-test") })
        val manager = DeviceManager(supervisor)
        val displayNetwork = RecordingDisplayNetworkBridge()
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            RuntimeDeviceImpl(
                deviceId = 42,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                manager = manager,
                gameTime = { 0L },
                displayNetwork = displayNetwork,
                stateSink = {},
                runtimeMetricsCollector = metrics,
            )
        val playerUuid = UUID.randomUUID()

        device.attachDisplaySession(playerUuid, containerId = 11, displayId = 1, width = 32, height = 16)
        device.turnOn()
        device.serverTick()

        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.tick.serverTickCalls)
        assertEquals(1, snapshot.tick.requestSliceCalls)
        assertTrue(snapshot.tick.displayFrameDrainCalls > 0, snapshot.summary())
        assertTrue(snapshot.tick.displayFlushCalls > 0, snapshot.summary())
        assertTrue(snapshot.tick.serverTickNanos > 0, snapshot.summary())
        assertTrue(snapshot.vm.sliceRequests > 0, snapshot.summary())

        device.close()
        manager.close()
    }

    @Test
    fun flushesNativeDisplayFrameBytesThroughServerTickWhenNativeLibraryIsConfigured() {
        if (System.getProperty("ckl.vm.native.library")?.isNotBlank() != true) return

        val supervisor = DeviceVmSupervisor(ServerWorldAccess { createTempDirectory("runtime-native-display-test") })
        val manager = DeviceManager(supervisor)
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            RuntimeDeviceImpl(
                deviceId = 42,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                manager = manager,
                gameTime = { 0L },
                displayNetwork = displayNetwork,
                stateSink = {},
            )
        val playerUuid = UUID.randomUUID()

        device.attachDisplaySession(playerUuid, containerId = 11, displayId = 1, width = 32, height = 16)
        device.turnOn()
        device.serverTick()

        assertTrue(displayNetwork.sentNativeFrameBytes.isNotEmpty(), "native pump should dispatch frame bytes on the server thread")

        device.close()
        manager.close()
    }

    private data class SentFrame(
        val playerUuid: UUID,
        val containerId: Int,
        val frame: DisplayFrameDelta,
    )

    private class RecordingDisplayNetworkBridge : DisplayNetworkBridge {
        val sentFrames = mutableListOf<SentFrame>()
        val sentNativeFrameBytes = mutableListOf<ByteArray>()

        override fun isDisplaySessionStillBound(
            playerUuid: UUID,
            containerId: Int,
            deviceId: Int,
            displayId: Int,
        ): Boolean = true

        override fun sendDisplayFrame(
            playerUuid: UUID,
            containerId: Int,
            frame: DisplayFrameDelta,
        ) {
            sentFrames += SentFrame(playerUuid, containerId, frame)
        }

        override fun sendNativeDisplayFrameBytes(
            playerUuid: UUID,
            containerId: Int,
            payload: ByteArray,
        ) {
            sentNativeFrameBytes += payload
        }
    }

    private class RecordingVmHandle : DeviceVmHandle {
        override val deviceId: Int = 42
        override val profile: DeviceProfile =
            DeviceProfile(
                id = "test",
                displayName = "Test",
                cpuBudgetNanosPerSlice = 10_000_000,
                maxEventQueueSize = 16,
                allowedCapabilities = setOf(DeviceCapability.FILESYSTEM),
                resources =
                    DeviceResources(
                        cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 10_000_000),
                        memory = DeviceMemoryResources(),
                        storage = DeviceStorageResources(),
                        queues = DeviceQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                    ),
            )
        var requestSliceCalls: Int = 0
            private set
        var lastServerTick: Long? = null
            private set

        override fun boot(): Boolean = true

        override fun stop(reason: VmStopReason) = Unit

        override fun enqueueEvent(event: VmEvent): Boolean = true

        override fun requestSlice(serverTick: Long) {
            requestSliceCalls += 1
            lastServerTick = serverTick
        }

        override fun snapshot(): VmSnapshot =
            VmSnapshot(
                deviceId = deviceId,
                profile = profile,
                state = VmState.Running,
                currentTick = 0L,
                queuedEvents = 0,
                pendingHostCalls = 0,
            )
    }
}
