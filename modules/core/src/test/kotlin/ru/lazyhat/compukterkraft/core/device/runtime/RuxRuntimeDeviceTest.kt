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
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.input.PasteInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeRuxComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeRuxComputerDisplaySnapshot
import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxComputerEndpoint
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun sendsRuxDisplaySnapshotFrameToAttachedDisplaySessions() {
        val endpoint = RecordingRuxEndpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            RuxRuntimeDevice(
                deviceId = 14,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()

        endpoint.displaySnapshot =
            NativeRuxComputerDisplaySnapshot(
                columns = 80,
                rows = 25,
                cursorX = 3,
                cursorY = 0,
                sequence = 1,
                cells = "RUX".encodeToByteArray() + ByteArray(80 * 25 - 3),
            )
        device.attachDisplaySession(playerUuid, containerId = 20, displayId = 1, width = 400, height = 200)
        device.turnOn()
        device.serverTick()
        device.serverTick()

        assertEquals(1, displayNetwork.sentFrames.size)
        val sent = displayNetwork.sentFrames.single()
        assertEquals(playerUuid, sent.playerUuid)
        assertEquals(20, sent.containerId)
        assertEquals(1, sent.frame.displayId)
        assertEquals(400, sent.frame.width)
        assertEquals(200, sent.frame.height)
        assertTrue(sent.frame.fullRefresh)
        assertTrue(sent.frame.tiles.single().payload.any { it != 0.toByte() })
    }

    @Test
    fun sendsCurrentRuxDisplaySnapshotWhenDisplaySessionReopensWithoutNewVmFrame() {
        val endpoint = RecordingRuxEndpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            RuxRuntimeDevice(
                deviceId = 15,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()
        endpoint.displaySnapshot =
            NativeRuxComputerDisplaySnapshot(
                columns = 80,
                rows = 25,
                cursorX = 0,
                cursorY = 0,
                sequence = 1,
                cells = "No Bootable Device".encodeToByteArray() + ByteArray(80 * 25 - "No Bootable Device".length),
            )

        device.attachDisplaySession(playerUuid, containerId = 20, displayId = 1, width = 400, height = 200)
        device.turnOn()
        device.serverTick()
        device.detachDisplaySession(playerUuid, displayId = 1)
        device.attachDisplaySession(playerUuid, containerId = 21, displayId = 1, width = 400, height = 200)
        device.serverTick()

        assertEquals(2, displayNetwork.sentFrames.size)
        assertEquals(listOf(20, 21), displayNetwork.sentFrames.map { it.containerId })
        assertTrue(displayNetwork.sentFrames.all { it.frame.fullRefresh })
        assertTrue(displayNetwork.sentFrames.last().frame.tiles.single().payload.any { it != 0.toByte() })
    }

    @Test
    fun dispatchesCharacterInputThroughSerialEchoToDisplayFrame() {
        val endpoint = RecordingRuxEndpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            RuxRuntimeDevice(
                deviceId = 10,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()

        device.attachDisplaySession(playerUuid, containerId = 18, displayId = 1, width = 36, height = 27)
        device.turnOn()
        DeviceEvents.dispatch(device, KeyInputEvent.Character('R'.code.toByte()))
        device.serverTick()

        assertEquals(listOf("R"), endpoint.inputs.map { it.decodeToString() })
        assertEquals(1, displayNetwork.sentFrames.size)
        assertTrue(displayNetwork.sentFrames.single().frame.tiles.single().payload.any { it != 0.toByte() })
    }

    @Test
    fun dispatchesPasteInputThroughSerialEchoToDisplayFrame() {
        val endpoint = RecordingRuxEndpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            RuxRuntimeDevice(
                deviceId = 11,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )

        device.attachDisplaySession(UUID.randomUUID(), containerId = 19, displayId = 1, width = 36, height = 27)
        device.turnOn()
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap("Rux".encodeToByteArray())))
        device.serverTick()

        assertEquals(listOf("Rux"), endpoint.inputs.map { it.decodeToString() })
        assertEquals(1, displayNetwork.sentFrames.size)
    }

    @Test
    fun dispatchesEnterKeyAsSerialNewline() {
        val endpoint = RecordingRuxEndpoint()
        val device =
            RuxRuntimeDevice(
                deviceId = 12,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        DeviceEvents.dispatch(device, KeyInputEvent.Down(KeyCodes.KEY_ENTER, repeat = false))

        assertEquals(listOf("\n"), endpoint.inputs.map { it.decodeToString() })
    }

    @Test
    fun dispatchesBackspaceKeyAsSerialBackspace() {
        val endpoint = RecordingRuxEndpoint()
        val device =
            RuxRuntimeDevice(
                deviceId = 13,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        DeviceEvents.dispatch(device, KeyInputEvent.Down(KeyCodes.KEY_BACKSPACE, repeat = false))

        assertEquals(listOf(listOf(0x08)), endpoint.inputs.map { it.map(Byte::toInt) })
    }

    @Test
    fun exposesRunningEndpointSnapshotForPersistence() {
        val endpoint = RecordingRuxEndpoint()
        endpoint.runtimeSnapshot = byteArrayOf(0x52, 0x55, 0x58)
        val device =
            RuxRuntimeDevice(
                deviceId = 16,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        assertEquals(null, device.snapshotRuntimeState())

        device.turnOn()

        assertContentEquals(byteArrayOf(0x52, 0x55, 0x58), device.snapshotRuntimeState())

        device.shutdown()

        assertEquals(null, device.snapshotRuntimeState())
    }

    private class RecordingRuxEndpoint : RuxComputerEndpoint {
        val inputs = mutableListOf<ByteArray>()
        var tickCalls = 0
            private set
        var closeCalls = 0
            private set
        var displaySnapshot: NativeRuxComputerDisplaySnapshot? = null
        var runtimeSnapshot: ByteArray = ByteArray(0)
        private var lastPolledDisplaySequence: Long? = null
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

        override fun display0Snapshot(): NativeRuxComputerDisplaySnapshot? = displaySnapshot

        override fun pollDisplay0Snapshot(): NativeRuxComputerDisplaySnapshot? {
            val snapshot = displaySnapshot ?: run {
                lastPolledDisplaySequence = null
                return null
            }
            if (lastPolledDisplaySequence == snapshot.sequence) {
                return null
            }
            lastPolledDisplaySequence = snapshot.sequence
            return snapshot
        }

        override fun clearOutput() = Unit

        override fun machineSnapshot(): ByteArray = runtimeSnapshot.copyOf()

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
