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
    fun runtimeDeviceAcceptsKeyboardInputThroughUserlandShell() {
        val device = createDevice()

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchText(device, "echo abc\n")
            waitForTerminalText(device, "abc")
            waitForTerminalText(device, "K16> echo abc")

            dispatchText(device, "ticks\n")
            waitForTerminalText(device, "TICKS ")

            dispatchText(device, "uname\n")
            waitForTerminal(device, "uname output and returned prompt") { terminal ->
                val unameCommandIndex = terminal.indexOf("K16> uname")
                val unameOutputIndex = terminal.indexOf("K16", startIndex = unameCommandIndex + "K16> uname".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = unameOutputIndex)
                unameCommandIndex >= 0 && unameOutputIndex > unameCommandIndex && returnedPromptIndex > unameOutputIndex
            }

            dispatchText(device, "cat /etc/motd\n")
            waitForTerminal(device, "cat output and returned prompt") { terminal ->
                val catCommandIndex = terminal.indexOf("K16> cat /etc/motd")
                val catOutputIndex = terminal.indexOf("K16 FS OK", startIndex = catCommandIndex + "K16> cat /etc/motd".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = catOutputIndex)
                catCommandIndex >= 0 && catOutputIndex > catCommandIndex && returnedPromptIndex > catOutputIndex
            }

            dispatchText(device, "ls /bin\n")
            waitForTerminal(device, "ls output and returned prompt") { terminal ->
                val lsCommandIndex = terminal.indexOf("K16> ls /bin")
                val lsOutputIndex = terminal.indexOf("ls.kx", startIndex = lsCommandIndex + "K16> ls /bin".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = lsOutputIndex)
                lsCommandIndex >= 0 && lsOutputIndex > lsCommandIndex && returnedPromptIndex > lsOutputIndex
            }

            dispatchText(device, "alloc\n")
            waitForTerminal(device, "alloc output and returned prompt") { terminal ->
                val allocCommandIndex = terminal.indexOf("K16> alloc")
                val allocOutputIndex = terminal.indexOf("ALLOC", startIndex = allocCommandIndex + "K16> alloc".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = allocOutputIndex)
                allocCommandIndex >= 0 && allocOutputIndex > allocCommandIndex && returnedPromptIndex > allocOutputIndex
            }

            val terminal = terminalText(requireNotNull(device.snapshotRuntimeState()))
            val promptIndex = terminal.indexOf("K16> ")
            val echoOutputIndex = terminal.indexOf("abc", startIndex = promptIndex)
            val ticksOutputIndex = terminal.indexOf("TICKS ", startIndex = echoOutputIndex)
            val unameCommandIndex = terminal.indexOf("K16> uname", startIndex = ticksOutputIndex)
            val unameOutputIndex = terminal.indexOf("K16", startIndex = unameCommandIndex + "K16> uname".length)
            val unameReturnedPromptIndex = terminal.indexOf("K16> ", startIndex = unameOutputIndex)
            val catCommandIndex = terminal.indexOf("K16> cat /etc/motd", startIndex = unameReturnedPromptIndex)
            val catOutputIndex = terminal.indexOf("K16 FS OK", startIndex = catCommandIndex + "K16> cat /etc/motd".length)
            val catReturnedPromptIndex = terminal.indexOf("K16> ", startIndex = catOutputIndex)
            val lsCommandIndex = terminal.indexOf("K16> ls /bin", startIndex = catReturnedPromptIndex)
            val lsOutputIndex = terminal.indexOf("ls.kx", startIndex = lsCommandIndex + "K16> ls /bin".length)
            val lsReturnedPromptIndex = terminal.indexOf("K16> ", startIndex = lsOutputIndex)
            val allocCommandIndex = terminal.indexOf("K16> alloc", startIndex = lsReturnedPromptIndex)
            val allocOutputIndex = terminal.indexOf("ALLOC", startIndex = allocCommandIndex + "K16> alloc".length)
            val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = allocOutputIndex)
            assertTrue(
                promptIndex >= 0 &&
                    echoOutputIndex > promptIndex &&
                    ticksOutputIndex > echoOutputIndex &&
                    unameCommandIndex > ticksOutputIndex &&
                    unameOutputIndex > unameCommandIndex &&
                    unameReturnedPromptIndex > unameOutputIndex &&
                    catCommandIndex >= unameReturnedPromptIndex &&
                    catOutputIndex > catCommandIndex &&
                    catReturnedPromptIndex > catOutputIndex &&
                    lsCommandIndex >= catReturnedPromptIndex &&
                    lsOutputIndex > lsCommandIndex &&
                    lsReturnedPromptIndex > lsOutputIndex &&
                    allocCommandIndex >= lsReturnedPromptIndex &&
                    allocOutputIndex > allocCommandIndex &&
                    returnedPromptIndex > allocOutputIndex,
                "userland shell should dispatch commands through fd stdin/stdout and return a prompt; terminal: $terminal",
            )
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceAcceptsLongHeapBackedEchoInputThroughUserlandShell() {
        val device = createDevice()
        val payload = "x".repeat(180)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchPasteText(device, "echo $payload\n")
            waitForTerminal(device, "long echo output and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> echo $payload")
                val outputIndex = terminal.indexOf(payload, startIndex = commandIndex + "K16> echo ".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex + payload.length)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }
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

    private fun dispatchPasteText(
        device: K16RuntimeDevice,
        text: String,
    ) {
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap(text.encodeToByteArray())))
        tickAndSync(device)
    }

    private fun waitForTerminalText(
        device: K16RuntimeDevice,
        expected: String,
    ) = waitForTerminal(device, "'$expected'") { terminal -> terminal.contains(expected) }

    private fun waitForTerminal(
        device: K16RuntimeDevice,
        description: String,
        predicate: (String) -> Boolean,
    ) {
        repeat(80) {
            tickAndSync(device)
            val snapshot = device.snapshotRuntimeState()
            if (snapshot != null && predicate(terminalText(snapshot))) return
            Thread.sleep(10)
        }
        val snapshot = device.snapshotRuntimeState()
        val terminal = snapshot?.let(::terminalText) ?: "<no snapshot>"
        error("K16 shell runtime smoke did not observe $description; terminal: $terminal")
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
