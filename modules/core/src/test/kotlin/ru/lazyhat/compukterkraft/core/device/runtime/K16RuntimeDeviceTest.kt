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
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        waitUntil { endpoint.tickCalls == 1 && device.serialOutputSnapshot().decodeToString() == "R" }

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
    fun serverTickDoesNotExecuteK16EndpointOnCallerThread() {
        val endpoint = RecordingK16Endpoint()
        val device =
            K16RuntimeDevice(
                deviceId = 19,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )
        val callerThreadId = Thread.currentThread().id

        device.turnOn()
        device.serverTick()
        waitUntil { endpoint.tickCalls == 1 }

        assertFalse(
            endpoint.tickThreadIds.contains(callerThreadId),
            "K16 endpoint execution must not run on the Minecraft server tick thread.",
        )
    }

    @Test
    fun overloadedK16EndpointDoesNotBlockServerTick() {
        val tickEntered = CountDownLatch(1)
        val releaseTick = CountDownLatch(1)
        val serverTickReturned = CountDownLatch(1)
        val endpoint =
            BlockingFirstTickK16Endpoint(
                tickEntered = tickEntered,
                releaseTick = releaseTick,
            )
        val device =
            K16RuntimeDevice(
                deviceId = 20,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
            )

        device.turnOn()
        val callerThread =
            Thread(
                {
                    device.serverTick()
                    serverTickReturned.countDown()
                },
                "test-server-tick-caller",
            )

        callerThread.start()

        assertTrue(
            serverTickReturned.await(200, TimeUnit.MILLISECONDS),
            "K16 serverTick must return while worker execution is still overloaded.",
        )
        assertTrue(
            tickEntered.await(2, TimeUnit.SECONDS),
            "Expected worker to enter the overloaded K16 endpoint tick.",
        )

        releaseTick.countDown()
        callerThread.join(2_000)
        waitUntil { endpoint.tickCalls == 1 }
        device.shutdown()
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
        waitUntil { endpoint.inputs.isNotEmpty() }

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
        waitUntil { device.serialOutputSnapshot().decodeToString() == "K16!" }
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
    fun sendsFramebufferFramesToAttachedDisplaySessions() {
        val endpoint = RecordingK16Endpoint()
        val displayNetwork = RecordingDisplayNetworkBridge()
        val device =
            K16RuntimeDevice(
                deviceId = 21,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = { endpoint },
                stateSink = {},
                displayNetwork = displayNetwork,
            )
        val playerUuid = UUID.randomUUID()
        val frame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 7,
                width = 320,
                height = 200,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles =
                    listOf(
                        DisplayTile(
                            tileX = 0,
                            tileY = 0,
                            x = 0,
                            y = 0,
                            width = 2,
                            height = 1,
                            payload = byteArrayOf(0xF8.toByte(), 0x00, 0x07, 0xE0.toByte()),
                        ),
                    ),
            )

        device.attachDisplaySession(playerUuid, containerId = 22, displayId = 1, width = 320, height = 200)
        device.turnOn()
        endpoint.enqueueFramebufferFrames(encodeDisplayFrames(listOf(frame)))
        device.serverTick()
        waitUntil {
            device.serverTick()
            displayNetwork.sentFrames.size == 1
        }

        assertEquals(1, displayNetwork.sentFrames.size)
        assertEquals(frame, displayNetwork.sentFrames.single().frame)
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
        waitUntil { endpoint.tickCalls == 1 }
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
        waitUntil { device.serialOutputSnapshot().decodeToString() == "R" }
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
        waitUntil { device.serialOutputSnapshot().decodeToString() == "K16" }
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
        waitUntil { endpoint.inputs.isNotEmpty() }

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
        waitUntil { endpoint.inputs.isNotEmpty() }

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

    private fun waitUntil(
        timeoutMillis: Long = 2_000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue(predicate(), "Condition was not met within ${timeoutMillis}ms.")
    }

    private open class RecordingK16Endpoint : K16ComputerEndpoint {
        val inputs: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        @Volatile
        var tickCalls = 0
            private set
        val tickThreadIds: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        @Volatile
        var closeCalls = 0
            private set
        var displaySnapshot: NativeK16ComputerDisplaySnapshot? = null
        var runtimeSnapshot: ByteArray = ByteArray(0)
        var control: NativeK16ComputerControl = NativeK16ComputerControl(status = K16RuntimeDevice.STATUS_READY, exitCode = 0, panicCode = 0)
        private var lastPolledDisplaySequence: Long? = null
        private val injectedOutput = StringBuilder()
        private val framebufferFrameBatches = ArrayDeque<ByteArray>()

        override fun pushInput(bytes: ByteArray) {
            inputs += bytes.copyOf()
        }

        override open fun tick(maxTurns: Int): NativeK16ComputerControl {
            tickCalls += 1
            tickThreadIds += Thread.currentThread().id
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

        override fun drainFramebuffer0Frames(): ByteArray =
            if (framebufferFrameBatches.isEmpty()) {
                ByteArray(0)
            } else {
                framebufferFrameBatches.removeFirst()
            }

        override fun clearOutput() = Unit

        override fun machineSnapshot(): ByteArray = runtimeSnapshot.copyOf()

        override fun close() {
            closeCalls += 1
        }

        fun injectOutput(text: String) {
            injectedOutput.append(text)
        }

        fun enqueueFramebufferFrames(bytes: ByteArray) {
            framebufferFrameBatches += bytes.copyOf()
        }
    }

    private fun encodeDisplayFrames(frames: List<DisplayFrameDelta>): ByteArray {
        val payloadBytes = frames.sumOf { frame -> frame.tiles.sumOf { it.payload.size } }
        val buffer = ByteBuffer.allocate(4 + frames.size * 31 + frames.sumOf { it.tiles.size * 28 } + payloadBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(frames.size)
        for (frame in frames) {
            buffer.putInt(frame.displayId)
            buffer.putLong(frame.sequence)
            buffer.putInt(frame.width)
            buffer.putInt(frame.height)
            buffer.put(
                when (frame.pixelFormat) {
                    DisplayPixelFormat.RGB565 -> 0
                },
            )
            buffer.put(if (frame.fullRefresh) 1 else 0)
            buffer.putInt(frame.tiles.size)
            for (tile in frame.tiles) {
                buffer.putInt(tile.tileX)
                buffer.putInt(tile.tileY)
                buffer.putInt(tile.x)
                buffer.putInt(tile.y)
                buffer.putInt(tile.width)
                buffer.putInt(tile.height)
                buffer.putInt(tile.payload.size)
                buffer.put(tile.payload)
            }
        }
        return buffer.array()
    }

    private class BlockingFirstTickK16Endpoint(
        private val tickEntered: CountDownLatch,
        private val releaseTick: CountDownLatch,
    ) : RecordingK16Endpoint() {
        private var firstTick = true

        override fun tick(maxTurns: Int): NativeK16ComputerControl {
            if (firstTick) {
                firstTick = false
                tickEntered.countDown()
                releaseTick.await(2, TimeUnit.SECONDS)
            }
            return super.tick(maxTurns)
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
