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
        val device = createDevice(deviceId = 226)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchText(device, "echo abc\n")
            waitForTerminalText(device, "abc")
            waitForTerminalText(device, "K16> echo abc")

            dispatchText(device, "ticks\n")
            waitForTerminalText(device, "TICKS ")

            dispatchText(device, "pwd\n")
            waitForTerminal(device, "initial pwd output and returned prompt") { terminal ->
                val pwdCommandIndex = terminal.indexOf("K16> pwd")
                val pwdOutputIndex = terminal.indexOf("/", startIndex = pwdCommandIndex + "K16> pwd".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = pwdOutputIndex)
                pwdCommandIndex >= 0 && pwdOutputIndex > pwdCommandIndex && returnedPromptIndex > pwdOutputIndex
            }

            dispatchText(device, "uname\n")
            waitForTerminal(device, "uname output and returned prompt") { terminal ->
                val unameCommandIndex = terminal.indexOf("K16> uname")
                val unameOutputIndex = terminal.indexOf("K16", startIndex = unameCommandIndex + "K16> uname".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = unameOutputIndex)
                unameCommandIndex >= 0 && unameOutputIndex > unameCommandIndex && returnedPromptIndex > unameOutputIndex
            }

            dispatchText(device, "cd etc\n")
            waitForTerminal(device, "cd etc returned prompt") { terminal ->
                val cdCommandIndex = terminal.indexOf("K16> cd etc")
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = cdCommandIndex + "K16> cd etc".length)
                cdCommandIndex >= 0 && returnedPromptIndex > cdCommandIndex
            }

            dispatchText(device, "pwd\n")
            waitForTerminal(device, "changed pwd output and returned prompt") { terminal ->
                val firstPwdPromptIndex = terminal.indexOf("K16> pwd")
                val pwdCommandIndex = terminal.indexOf("K16> pwd", startIndex = firstPwdPromptIndex + "K16> pwd".length)
                val pwdOutputIndex = terminal.indexOf("/etc", startIndex = pwdCommandIndex + "K16> pwd".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = pwdOutputIndex)
                pwdCommandIndex >= 0 && pwdOutputIndex > pwdCommandIndex && returnedPromptIndex > pwdOutputIndex
            }

            dispatchText(device, "cat motd\n")
            waitForTerminal(device, "relative cat output and returned prompt") { terminal ->
                val catCommandIndex = terminal.indexOf("K16> cat motd")
                val catOutputIndex = terminal.indexOf("K16 FS OK", startIndex = catCommandIndex + "K16> cat motd".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = catOutputIndex)
                catCommandIndex >= 0 && catOutputIndex > catCommandIndex && returnedPromptIndex > catOutputIndex
            }

            dispatchText(device, "cat motd motd\n")
            waitForTerminal(device, "multi-argv relative cat output and returned prompt") { terminal ->
                val catCommandIndex = terminal.indexOf("K16> cat motd motd")
                val firstOutputIndex =
                    terminal.indexOf("K16 FS OK", startIndex = catCommandIndex + "K16> cat motd motd".length)
                val secondOutputIndex = terminal.indexOf("K16 FS OK", startIndex = firstOutputIndex + "K16 FS OK".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = secondOutputIndex)
                catCommandIndex >= 0 &&
                    firstOutputIndex > catCommandIndex &&
                    secondOutputIndex > firstOutputIndex &&
                    returnedPromptIndex > secondOutputIndex
            }

            dispatchText(device, "cd motd\n")
            waitForTerminal(device, "cd rejects regular file and returned prompt") { terminal ->
                val cdCommandIndex = terminal.indexOf("K16> cd motd")
                val errorIndex = terminal.indexOf("ERR INVAL", startIndex = cdCommandIndex + "K16> cd motd".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = errorIndex)
                cdCommandIndex >= 0 && errorIndex > cdCommandIndex && returnedPromptIndex > errorIndex
            }

            dispatchText(device, "pwd\n")
            waitForTerminal(device, "failed cd keeps pwd and returned prompt") { terminal ->
                val secondPwdPromptIndex = terminal.indexOf("K16> pwd")
                val thirdPwdPromptIndex = terminal.indexOf("K16> pwd", startIndex = secondPwdPromptIndex + "K16> pwd".length)
                val pwdCommandIndex = terminal.indexOf("K16> pwd", startIndex = thirdPwdPromptIndex + "K16> pwd".length)
                val pwdOutputIndex = terminal.indexOf("/etc", startIndex = pwdCommandIndex + "K16> pwd".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = pwdOutputIndex)
                pwdCommandIndex >= 0 && pwdOutputIndex > pwdCommandIndex && returnedPromptIndex > pwdOutputIndex
            }

            dispatchText(device, "ls /bin\n")
            waitForTerminal(device, "ls output and returned prompt") { terminal ->
                val lsCommandIndex = terminal.indexOf("K16> ls /bin")
                val lsOutputIndex = terminal.indexOf("ls.kx", startIndex = lsCommandIndex + "K16> ls /bin".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = lsOutputIndex)
                lsCommandIndex >= 0 && lsOutputIndex > lsCommandIndex && returnedPromptIndex > lsOutputIndex
            }

            dispatchText(device, "ls ../bin\n")
            waitForTerminal(device, "relative ls output and returned prompt") { terminal ->
                val lsCommandIndex = terminal.indexOf("K16> ls ../bin")
                val lsOutputIndex = terminal.indexOf("ls.kx", startIndex = lsCommandIndex + "K16> ls ../bin".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = lsOutputIndex)
                lsCommandIndex >= 0 && lsOutputIndex > lsCommandIndex && returnedPromptIndex > lsOutputIndex
            }

            dispatchText(device, "ls /\n")
            waitForTerminal(device, "root ls output marks directories and returned prompt") { terminal ->
                val rootLsCommandIndex = terminal.indexOf("K16> ls /")
                val rootLsOutputIndex = terminal.indexOf("bin/", startIndex = rootLsCommandIndex + "K16> ls /".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = rootLsOutputIndex)
                rootLsCommandIndex >= 0 && rootLsOutputIndex > rootLsCommandIndex && returnedPromptIndex > rootLsOutputIndex
            }

            dispatchText(device, "ls / /bin\n")
            waitForTerminal(device, "multi-argv ls output and returned prompt") { terminal ->
                val lsCommandIndex = terminal.indexOf("K16> ls / /bin")
                val rootOutputIndex = terminal.indexOf("bin/", startIndex = lsCommandIndex + "K16> ls / /bin".length)
                val binOutputIndex = terminal.indexOf("ls.kx", startIndex = rootOutputIndex + "bin/".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = binOutputIndex)
                lsCommandIndex >= 0 &&
                    rootOutputIndex > lsCommandIndex &&
                    binOutputIndex > rootOutputIndex &&
                    returnedPromptIndex > binOutputIndex
            }

            dispatchText(device, "nosuch\n")
            waitForTerminal(device, "generic missing executable reports no entry and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> nosuch")
                val outputIndex = terminal.indexOf("ERR NOENT", startIndex = commandIndex + "K16> nosuch".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "alloc\n")
            waitForTerminal(device, "alloc output and returned prompt") { terminal ->
                val allocCommandIndex = terminal.indexOf("K16> alloc")
                val allocOutputIndex = terminal.indexOf("ALLOC", startIndex = allocCommandIndex + "K16> alloc".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = allocOutputIndex)
                allocCommandIndex >= 0 && allocOutputIndex > allocCommandIndex && returnedPromptIndex > allocOutputIndex
            }

            val terminal = terminalText(requireNotNull(device.snapshotRuntimeState()))
            assertOrderedFragments(
                terminal,
                listOf(
                    "K16> ls /",
                    "bin/",
                    "K16> ls / /bin",
                    "bin/",
                    "ls.kx",
                    "K16> nosuch",
                    "ERR NOENT",
                    "K16> alloc",
                    "ALLOC",
                    "K16> ",
                ),
            )
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceAcceptsLongHeapBackedEchoInputThroughUserlandShell() {
        val device = createDevice(deviceId = 227)
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

    @Test
    fun runtimeDeviceRunsExplicitExecutablePathsThroughUserlandShell() {
        val device = createDevice(deviceId = 228)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchText(device, "/bin/uname.kx\n")
            waitForTerminal(device, "absolute executable path output and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> /bin/uname.kx")
                val outputIndex = terminal.indexOf("K16", startIndex = commandIndex + "K16> /bin/uname.kx".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cd /bin\n")
            waitForTerminal(device, "cd bin returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> cd /bin")
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = commandIndex + "K16> cd /bin".length)
                commandIndex >= 0 && returnedPromptIndex > commandIndex
            }

            dispatchText(device, "./uname.kx\n")
            waitForTerminal(device, "cwd relative executable path output and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> ./uname.kx")
                val outputIndex = terminal.indexOf("K16", startIndex = commandIndex + "K16> ./uname.kx".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "/bin/nosuch.kx\n")
            waitForTerminal(device, "missing absolute executable path reports no entry and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> /bin/nosuch.kx")
                val outputIndex = terminal.indexOf("ERR NOENT", startIndex = commandIndex + "K16> /bin/nosuch.kx".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }
        } finally {
            device.close()
        }
    }

    private fun createDevice(deviceId: Int): K16RuntimeDevice {
        val workspace = createTempDirectory("k16-shell-runtime-smoke-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        return K16RuntimeDevice(
            deviceId = deviceId,
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

    private fun assertOrderedFragments(
        terminal: String,
        fragments: List<String>,
    ) {
        var cursor = 0
        for (fragment in fragments) {
            val index = terminal.indexOf(fragment, startIndex = cursor)
            assertTrue(index >= 0, "missing fragment '$fragment' after $cursor; terminal: $terminal")
            cursor = index + fragment.length
        }
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
