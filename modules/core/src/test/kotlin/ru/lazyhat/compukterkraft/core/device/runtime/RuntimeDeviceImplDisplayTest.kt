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
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeDeviceImplDisplayTest {
    @Test
    fun flushesDisplayFramesToAttachedSession() {
        val supervisor = DeviceVmSupervisor(ServerWorldAccess { createTempDirectory("runtime-display-test") })
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

        assertTrue(displayNetwork.sentFrames.isNotEmpty())
        val (_, containerId, frame) = displayNetwork.sentFrames.single()
        assertEquals(11, containerId)
        assertEquals(1, frame.displayId)
        assertEquals(32, frame.width)
        assertEquals(16, frame.height)

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
    }

}
