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

package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.input.PasteInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.K16RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertTrue

class K16RuntimeTextIoProfilingTest {
    @Test
    fun printsK16TextIoRuntimeSummary() {
        val workspace = createTempDirectory("k16-runtime-text-io-profile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 226,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "text-io-profiling"),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                    )
                },
                stateSink = {},
                metricsCollector = metrics,
            )

        try {
            device.turnOn()
            waitForTerminal(device, "initial shell prompt") { terminal -> terminal.contains("K16> ") }
            val typedCommand = "ticks\n"
            dispatchText(device, typedCommand)
            waitForTerminal(device, "typed ticks output") { terminal -> terminal.contains("TICKS ") }
            val pastedCommand = "echo text-io-profile\n"
            dispatchPasteText(device, pastedCommand)
            waitForTerminal(device, "pasted echo output") { terminal -> terminal.contains("text-io-profile") }

            val snapshot = metrics.snapshot()
            val summary = snapshot.summary()
            println(summary)

            assertTrue(snapshot.vm.k16TextInputEvents >= typedCommand.encodeToByteArray().size + 1)
            val expectedInputBytes = typedCommand.encodeToByteArray().size + pastedCommand.encodeToByteArray().size
            assertTrue(snapshot.vm.k16TextInputBytes >= expectedInputBytes)
            assertTrue(snapshot.vm.k16TextInputNanos >= 0)
            assertTrue(snapshot.vm.k16SerialOutputSnapshots > 0)
            assertTrue(snapshot.vm.k16SerialOutputSnapshotBytes > 0)
            assertTrue(snapshot.k16.gpu.blitBufferCommands > 0)
            assertTrue(snapshot.k16.gpu.blitSourceBytes > 0)
            assertTrue(snapshot.k16.gpu.presentCommands > 0)
            assertTrue(snapshot.k16.gpu.frames > 0)
            assertTrue(snapshot.k16.gpu.framePayloadBytes > 0)
            assertTrue(summary.contains("k16TextOutput: snapshots="))
            assertTrue(summary.contains("k16Gpu: blits="))
            assertTrue(summary.contains("k16TextInput: events="))
        } finally {
            device.close()
        }
    }

    private fun dispatchText(
        device: K16RuntimeDevice,
        text: String,
    ) {
        for (byte in text.encodeToByteArray()) {
            DeviceEvents.dispatch(device, KeyInputEvent.Character(byte))
        }
        tickAndSync(device)
    }

    private fun dispatchPasteText(
        device: K16RuntimeDevice,
        text: String,
    ) {
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap(text.encodeToByteArray())))
        tickAndSync(device)
    }

    private fun waitForTerminal(
        device: K16RuntimeDevice,
        description: String,
        predicate: (String) -> Boolean,
    ) {
        repeat(400) {
            tickAndSync(device)
            val snapshot = device.snapshotRuntimeState()
            if (snapshot != null && predicate(terminalText(snapshot))) return
            Thread.sleep(10)
        }
        val snapshot = device.snapshotRuntimeState()
        val terminal = snapshot?.let(::terminalText) ?: "<no snapshot>"
        error("K16 text IO profiling did not observe $description; terminal: $terminal")
    }

    private fun tickAndSync(device: K16RuntimeDevice) {
        device.serverTick()
        device.snapshotRuntimeState()
    }

    private fun terminalText(snapshot: ByteArray): String =
        snapshotRamBytes(snapshot, start = K16_TERMINAL_CELLS_ADDR, size = K16_TERMINAL_ROWS * K16_TERMINAL_COLUMNS)
            .map { byte -> if (byte in 0x20..0x7e) byte.toInt().toChar() else ' ' }
            .joinToString(separator = "")

    private fun snapshotRamBytes(
        snapshot: ByteArray,
        start: Int,
        size: Int,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        require(snapshot.copyOfRange(0, 8).contentEquals("K16SNAP\u0000".encodeToByteArray()))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        require(start >= 0 && size >= 0 && start + size <= ramSize)
        return snapshot.copyOfRange(headerSize + start, headerSize + start + size)
    }
}

private const val K16_TERMINAL_CELLS_ADDR = 0x3000
private const val K16_TERMINAL_COLUMNS = 53
private const val K16_TERMINAL_ROWS = 25
