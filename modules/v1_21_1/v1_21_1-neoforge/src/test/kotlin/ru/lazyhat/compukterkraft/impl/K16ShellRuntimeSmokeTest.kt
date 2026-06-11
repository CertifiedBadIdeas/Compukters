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
import ru.lazyhat.compukterkraft.core.device.runtime.K16RuntimeDevice
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertTrue

class K16ShellRuntimeSmokeTest {
    @Test
    fun runtimeDeviceAcceptsKeyboardInputAndReturnsShellPrompt() {
        val device = createDevice()

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchText(device, "help\n")
            waitForTerminalText(device, "HELP")
            waitForTerminalText(device, "TICKS")
            waitForTerminalText(device, "K16> help")

            val terminal = terminalText(requireNotNull(device.snapshotRuntimeState()))
            val commandPromptIndex = terminal.indexOf("K16> help")
            val helpOutputIndex = terminal.indexOf("HELP", startIndex = commandPromptIndex)
            val ticksOutputIndex = terminal.indexOf("TICKS", startIndex = helpOutputIndex)
            val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = ticksOutputIndex)
            assertTrue(
                commandPromptIndex >= 0 &&
                    helpOutputIndex > commandPromptIndex &&
                    ticksOutputIndex > helpOutputIndex &&
                    returnedPromptIndex > ticksOutputIndex,
                "shell should echo UI-style keyboard input and return a prompt after command output; terminal: $terminal",
            )
        } finally {
            device.close()
        }
    }

    private fun createDevice(): K16RuntimeDevice {
        val workspace = createTempDirectory("k16-shell-runtime-smoke-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        return K16RuntimeDevice(
            deviceId = 226,
            properties = DeviceProperties(DeviceFamily.NORMAL, label = "shell-smoke"),
            endpointFactory = {
                K16ComputerRuntimeFactory.createFromBiosFlash(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                )
            },
            stateSink = {},
        )
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

    private fun waitForTerminalText(
        device: K16RuntimeDevice,
        expected: String,
    ) {
        repeat(80) {
            tickAndSync(device)
            val snapshot = device.snapshotRuntimeState()
            if (snapshot != null && terminalText(snapshot).contains(expected)) return
            Thread.sleep(10)
        }
        val terminal = device.snapshotRuntimeState()?.let(::terminalText) ?: "<no snapshot>"
        error("K16 shell runtime smoke did not observe '$expected'; terminal: $terminal")
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

private const val K16_TERMINAL_CELLS_ADDR = 0x8000
private const val K16_TERMINAL_COLUMNS = 53
private const val K16_TERMINAL_ROWS = 25
