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
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerDisplaySnapshot
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerEndpoint
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class K16RuntimeDeviceTest {
    private val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { it.resolve("gradle/libs.versions.toml").exists() }

    @Test
    fun runtimeDeviceUsesK16TypeName() {
        val k16RuntimeDevicePath =
            root.resolve(
                Path.of(
                    "modules",
                    "core",
                    "src",
                    "main",
                    "kotlin",
                    "ru",
                    "lazyhat",
                    "compukterkraft",
                    "core",
                    "device",
                    "runtime",
                    "K16RuntimeDevice.kt",
                ),
            )
        val legacyRuntimeDevicePath =
            root.resolve(
                Path.of(
                    "modules",
                    "core",
                    "src",
                    "main",
                    "kotlin",
                    "ru",
                    "lazyhat",
                    "compukterkraft",
                    "core",
                    "device",
                    "runtime",
                    "RuxRuntimeDevice.kt",
                ),
            )

        assertTrue(k16RuntimeDevicePath.exists())
        assertFalse(legacyRuntimeDevicePath.exists())

        val source = k16RuntimeDevicePath.readText()
        assertTrue(source.contains("class K16RuntimeDevice"))
        assertFalse(source.contains("class RuxRuntimeDevice"))
    }

    @Test
    fun ownsK16EndpointAndTicksItWhilePoweredOn() {
        val endpoint = RecordingK16Endpoint()
        val powerChanges = mutableListOf<Boolean>()
        val device =
            K16RuntimeDevice(
                deviceId = 7,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "K16"),
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
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
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
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 9,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()

        device.attachDisplaySession(playerUuid, containerId = 17, displayId = 1, width = 36, height = 27)
        device.turnOn()
        endpoint.injectOutput("K16!")
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
    fun sendsK16DisplaySnapshotFrameToAttachedDisplaySessions() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 14,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()

        endpoint.displaySnapshot =
            NativeK16ComputerDisplaySnapshot(
                columns = 80,
                rows = 25,
                cursorX = 3,
                cursorY = 0,
                sequence = 1,
                cells = "K16".encodeToByteArray() + ByteArray(80 * 25 - 3),
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
    fun sendsCurrentK16DisplaySnapshotWhenDisplaySessionReopensWithoutNewVmFrame() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 15,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()
        endpoint.displaySnapshot =
            NativeK16ComputerDisplaySnapshot(
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
    fun stopsTickingEndpointAfterTerminalControlStatus() {
        val endpoint = RecordingK16Endpoint()
        endpoint.control = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_HALTED, exitCode = 0, panicCode = 2)
        val device =
            K16RuntimeDevice(
                deviceId = 18,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        device.serverTick()
        device.serverTick()

        assertEquals(1, endpoint.tickCalls)
    }

    @Test
    fun dispatchesCharacterInputThroughSerialEchoToDisplayFrame() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
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
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 11,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )

        device.attachDisplaySession(UUID.randomUUID(), containerId = 19, displayId = 1, width = 36, height = 27)
        device.turnOn()
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap("K16".encodeToByteArray())))
        device.serverTick()

        assertEquals(listOf("K16"), endpoint.inputs.map { it.decodeToString() })
        assertEquals(1, displayNetwork.sentFrames.size)
    }

    @Test
    fun dispatchesEnterKeyAsSerialNewline() {
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
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
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
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
        val endpoint = RecordingK16Endpoint()
        endpoint.runtimeSnapshot = byteArrayOf(0x52, 0x55, 0x58)
        val device =
            K16RuntimeDevice(
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

    @Test
    fun failedEndpointStartupRecordsRuntimeFailureWithoutPoweringOn() {
        var endpointFactoryCalls = 0
        val powerChanges = mutableListOf<Boolean>()
        val device =
            K16RuntimeDevice(
                deviceId = 17,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = {
                    endpointFactoryCalls += 1
                    error("unsupported snapshot version")
                },
                stateSink = { powerChanges += it },
            )

        device.turnOn()

        assertFalse(device.isOn)
        assertEquals(1, endpointFactoryCalls)
        assertEquals(listOf(false), powerChanges)
        assertEquals("unsupported snapshot version", device.runtimeFailureMessage)
        assertEquals(null, device.snapshotRuntimeState())
    }

    private class RecordingK16Endpoint : K16ComputerEndpoint {
        val inputs = mutableListOf<ByteArray>()
        var tickCalls = 0
            private set
        var closeCalls = 0
            private set
        var displaySnapshot: NativeK16ComputerDisplaySnapshot? = null
        var runtimeSnapshot: ByteArray = ByteArray(0)
        var control: NativeK16ComputerControl = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0)
        private var lastPolledDisplaySequence: Long? = null
        private val injectedOutput = StringBuilder()

        override fun pushInput(bytes: ByteArray) {
            inputs += bytes.copyOf()
        }

        override fun tick(maxTurns: Int): NativeK16ComputerControl {
            tickCalls += 1
            return control
        }

        override fun outputSnapshot(): ByteArray =
            (inputs.fold(ByteArray(0)) { acc, bytes -> acc + bytes }.decodeToString() + injectedOutput)
                .encodeToByteArray()

        override fun display0Snapshot(): NativeK16ComputerDisplaySnapshot? = displaySnapshot

        override fun pollDisplay0Snapshot(): NativeK16ComputerDisplaySnapshot? {
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
