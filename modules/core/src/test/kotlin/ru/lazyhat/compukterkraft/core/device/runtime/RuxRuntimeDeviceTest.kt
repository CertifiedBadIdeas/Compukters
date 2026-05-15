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
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeRuxComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxComputerEndpoint
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuxRuntimeDeviceTest {
    @Test
    fun ownsRuxEndpointAndTicksItWhilePoweredOn() {
        val endpoint = RecordingRuxEndpoint()
        val powerChanges = mutableListOf<Boolean>()
        val device =
            RuxRuntimeDevice(
                deviceId = 7,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "Rux"),
                endpointFactory = { endpoint },
                stateSink = { powerChanges += it },
            )

        device.turnOn()
        device.queueEvent("char", arrayOf(byteArrayOf('R'.code.toByte())))
        device.serverTick()

        assertTrue(device.isOn)
        assertEquals(1, endpoint.tickCalls)
        assertEquals(listOf("R"), endpoint.inputs.map { it.decodeToString() })
        assertEquals("R", device.serialOutputSnapshot().decodeToString())
        assertEquals(listOf(true), powerChanges)

        device.shutdown()

        assertFalse(device.isOn)
        assertEquals(1, endpoint.closeCalls)
        assertEquals(listOf(true, false), powerChanges)
    }

    @Test
    fun mapsPasteEventsToSerialBytesWithoutConsumingCallerBuffer() {
        val endpoint = RecordingRuxEndpoint()
        val device =
            RuxRuntimeDevice(
                deviceId = 8,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )
        val paste = ByteBuffer.wrap("Hi".encodeToByteArray())

        device.turnOn()
        device.queueEvent("paste", arrayOf(paste))

        assertEquals("Hi", endpoint.inputs.single().decodeToString())
        assertEquals(0, paste.position())
    }

    @Test
    fun sendsSerialOutputFrameToAttachedDisplaySessions() {
        val endpoint = RecordingRuxEndpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            RuxRuntimeDevice(
                deviceId = 9,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()

        device.attachDisplaySession(playerUuid, containerId = 17, displayId = 1, width = 36, height = 27)
        device.turnOn()
        endpoint.injectOutput("Rux!")
        device.serverTick()

        assertEquals(1, displayNetwork.sentFrames.size)
        val sent = displayNetwork.sentFrames.single()
        assertEquals(playerUuid, sent.playerUuid)
        assertEquals(17, sent.containerId)
        assertEquals(1, sent.frame.displayId)
        assertEquals(36, sent.frame.width)
        assertEquals(27, sent.frame.height)
        assertTrue(sent.frame.fullRefresh)
    }

    private class RecordingRuxEndpoint : RuxComputerEndpoint {
        val inputs = mutableListOf<ByteArray>()
        var tickCalls = 0
            private set
        var closeCalls = 0
            private set
        private val injectedOutput = StringBuilder()

        override fun pushInput(bytes: ByteArray) {
            inputs += bytes.copyOf()
        }

        override fun tick(maxTurns: Int): NativeRuxComputerControl {
            tickCalls += 1
            return NativeRuxComputerControl(status = RuxRuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0)
        }

        override fun outputSnapshot(): ByteArray =
            (inputs.fold(ByteArray(0)) { acc, bytes -> acc + bytes }.decodeToString() + injectedOutput)
                .encodeToByteArray()

        override fun clearOutput() = Unit

        override fun close() {
            closeCalls += 1
        }

        fun injectOutput(text: String) {
            injectedOutput.append(text)
        }
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
