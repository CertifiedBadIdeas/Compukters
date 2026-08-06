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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
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

            dispatchText(device, "cp motd motd.copy\n")
            waitForTerminal(device, "relative cp output and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> cp motd motd.copy")
                val outputIndex =
                    terminal.indexOf(
                        "COPIED /etc/motd /etc/motd.copy",
                        startIndex = commandIndex + "K16> cp motd motd.copy".length,
                    )
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cat motd.copy\n")
            waitForTerminal(device, "relative cat output for copied file and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> cat motd.copy")
                val outputIndex = terminal.indexOf("K16 FS OK", startIndex = commandIndex + "K16> cat motd.copy".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
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

            dispatchText(device, "stat /etc/motd\n")
            waitForTerminal(device, "stat regular file metadata and returned prompt") { terminal ->
                val statCommandIndex = terminal.indexOf("K16> stat /etc/motd")
                val statOutputIndex =
                    terminal.indexOf("FILE 10 /etc/motd", startIndex = statCommandIndex + "K16> stat /etc/motd".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = statOutputIndex)
                statCommandIndex >= 0 && statOutputIndex > statCommandIndex && returnedPromptIndex > statOutputIndex
            }

            dispatchText(device, "stat /bin\n")
            waitForTerminal(device, "stat directory metadata and returned prompt") { terminal ->
                val statCommandIndex = terminal.indexOf("K16> stat /bin")
                val dirPrefixIndex = terminal.indexOf("DIR ", startIndex = statCommandIndex + "K16> stat /bin".length)
                val pathIndex = terminal.indexOf(" /bin", startIndex = dirPrefixIndex + "DIR ".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = pathIndex)
                statCommandIndex >= 0 && dirPrefixIndex > statCommandIndex && pathIndex > dirPrefixIndex &&
                    returnedPromptIndex > pathIndex
            }

            dispatchText(device, "stat /nosuch\n")
            waitForTerminal(device, "stat missing path reports no entry and returned prompt") { terminal ->
                val statCommandIndex = terminal.indexOf("K16> stat /nosuch")
                val errorIndex =
                    terminal.indexOf("ERR NOENT /nosuch", startIndex = statCommandIndex + "K16> stat /nosuch".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = errorIndex)
                statCommandIndex >= 0 && errorIndex > statCommandIndex && returnedPromptIndex > errorIndex
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
                val pwdCommandIndex = terminal.lastPhysicalIndexOf("K16> pwd")
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
            waitForTerminal(device, "production image reports missing dev-only alloc program and returned prompt") { terminal ->
                val allocCommandIndex = terminal.indexOf("K16> alloc")
                val allocOutputIndex = terminal.indexOf("ERR NOENT", startIndex = allocCommandIndex + "K16> alloc".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = allocOutputIndex)
                allocCommandIndex >= 0 && allocOutputIndex > allocCommandIndex && returnedPromptIndex > allocOutputIndex
            }

            val terminal = terminalText(requireNotNull(device.snapshotRuntimeState()))
            assertOrderedFragments(
                terminal,
                listOf(
                    "K16> ls / /bin",
                    "bin/",
                    "ls.kx",
                    "K16> nosuch",
                    "ERR NOENT",
                    "K16> alloc",
                    "ERR NOENT",
                    "K16> ",
                ),
            )
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceReportsNonZeroChildExitStatusThroughUserlandShell() {
        val device = createDevice(deviceId = 241)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            runShellCommandAndWait(
                device,
                cursor = 0,
                command = "cat /etc/missing",
                description = "cat missing file reports child exit status and returns prompt",
                "ERR EXIT 1",
            )
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceRestartsShellAfterExitCommand() {
        val device = createDevice(deviceId = 244)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            var cursor = 0
            cursor = runShellCommandAndWait(device, cursor, "exit nope", "invalid exit keeps shell running", "ERR INVAL")
            cursor = runShellCommandAndWait(device, cursor, "status", "invalid exit status is remembered", "STATUS INVAL")
            cursor = runShellCommandAndWait(device, cursor, "exit", "clean shell exit restarts through init", "K16 SHELL")
            runShellCommandAndWait(device, cursor, "status", "restarted shell has clean status", "STATUS 0")
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceTracksLastCommandStatusThroughUserlandShell() {
        val device = createDevice(deviceId = 242)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            var cursor = 0
            cursor = runShellCommandAndWait(device, cursor, "status", "initial status is zero", "STATUS 0")
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "cat /etc/missing",
                    "missing cat reports child exit status",
                    "ERR EXIT 1",
                )
            cursor = runShellCommandAndWait(device, cursor, "status", "child exit status is remembered", "STATUS 1")
            cursor = runShellCommandAndWait(device, cursor, "echo ok", "successful builtin resets status", "ok")
            cursor = runShellCommandAndWait(device, cursor, "status", "status resets after successful builtin", "STATUS 0")
            cursor = runShellCommandAndWait(device, cursor, "nosuch", "missing executable reports no entry", "ERR NOENT")
            runShellCommandAndWait(device, cursor, "status", "launch error status is remembered by name", "STATUS NOENT")
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceContinuesMultiPathCoreutilsAfterPerPathErrors() {
        val device = createDevice(deviceId = 243)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            var cursor = 0
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "cat /etc/missing /etc/motd",
                    "cat reports missing path and still prints later existing file",
                    "cat: open failed: /etc/missing",
                    "K16 FS OK",
                    "ERR EXIT 1",
                )
            cursor = runShellCommandAndWait(device, cursor, "status", "cat mixed result is remembered", "STATUS 1")
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "stat /etc/missing /etc/motd",
                    "stat reports missing path and still prints later existing metadata",
                    "ERR NOENT /etc/missing",
                    "FILE 10 /etc/motd",
                    "ERR EXIT 1",
                )
            cursor = runShellCommandAndWait(device, cursor, "status", "stat mixed result is remembered", "STATUS 1")
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "ls /etc/missing /bin",
                    "ls reports missing path and still lists later existing directory",
                    "ERR NOENT /etc/missing",
                    "ls.kx",
                    "ERR EXIT 1",
                )
            runShellCommandAndWait(device, cursor, "status", "ls mixed result is remembered", "STATUS 1")
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
    fun runtimeDeviceWritesRegularFileThroughUserlandShell() {
        val device = createDevice(deviceId = 233)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchText(device, "write /etc/user.txt hello\n")
            waitForTerminal(device, "write output and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> write /etc/user.txt hello")
                val outputIndex =
                    terminal.indexOf("WROTE 5 /etc/user.txt", startIndex = commandIndex + "K16> write /etc/user.txt hello".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cat /etc/user.txt\n")
            waitForTerminal(device, "cat output for written file and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> cat /etc/user.txt")
                val outputIndex = terminal.indexOf("hello", startIndex = commandIndex + "K16> cat /etc/user.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "stat /etc/user.txt\n")
            waitForTerminal(device, "stat output for written file and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> stat /etc/user.txt")
                val outputIndex =
                    terminal.indexOf("FILE 5 /etc/user.txt", startIndex = commandIndex + "K16> stat /etc/user.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "write --append /etc/user.txt -world\n")
            waitForTerminal(device, "append write output and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> write --append /etc/user.txt -world")
                val outputIndex =
                    terminal.indexOf(
                        "WROTE 6 /etc/user.txt",
                        startIndex = commandIndex + "K16> write --append /etc/user.txt -world".length,
                    )
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cat /etc/user.txt\n")
            waitForTerminal(device, "cat output for appended file and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> cat /etc/user.txt")
                val outputIndex = terminal.indexOf("hello-world", startIndex = commandIndex + "K16> cat /etc/user.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "stat /etc/user.txt\n")
            waitForTerminal(device, "stat output for appended file and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> stat /etc/user.txt")
                val outputIndex =
                    terminal.indexOf("FILE 11 /etc/user.txt", startIndex = commandIndex + "K16> stat /etc/user.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cp /etc/user.txt /etc/user-copy.txt\n")
            waitForTerminal(device, "cp output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> cp /etc/user.txt /etc/user-copy.txt")
                val outputIndex =
                    terminal.indexOf(
                        "COPIED /etc/user.txt /etc/user-copy.txt",
                        startIndex = commandIndex + "K16> cp /etc/user.txt /etc/user-copy.txt".length,
                    )
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cat /etc/user-copy.txt\n")
            waitForTerminal(device, "cat output for copied file and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> cat /etc/user-copy.txt")
                val outputIndex = terminal.indexOf("hello-world", startIndex = commandIndex + "K16> cat /etc/user-copy.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "stat /etc/user-copy.txt\n")
            waitForTerminal(device, "stat output for copied file and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> stat /etc/user-copy.txt")
                val outputIndex =
                    terminal.indexOf("FILE 11 /etc/user-copy.txt", startIndex = commandIndex + "K16> stat /etc/user-copy.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cp /etc/user.txt\n")
            waitForTerminal(device, "cp invalid usage error and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> cp /etc/user.txt")
                val outputIndex = terminal.indexOf("ERR INVAL", startIndex = commandIndex + "K16> cp /etc/user.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cp /etc/missing.txt /etc/missing-copy.txt\n")
            waitForTerminal(device, "cp missing source error and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> cp /etc/missing.txt /etc/missing-copy.txt")
                val outputIndex =
                    terminal.indexOf(
                        "ERR NOENT /etc/missing.txt",
                        startIndex = commandIndex + "K16> cp /etc/missing.txt /etc/missing-copy.txt".length,
                    )
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "mv /etc/user-copy.txt /etc/user-moved.txt\n")
            waitForTerminal(device, "mv output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> mv /etc/user-copy.txt /etc/user-moved.txt")
                val outputIndex =
                    terminal.indexOf(
                        "MOVED /etc/user-copy.txt /etc/user-moved.txt",
                        startIndex = commandIndex + "K16> mv /etc/user-copy.txt /etc/user-moved.txt".length,
                    )
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "stat /etc/user-copy.txt\n")
            waitForTerminal(device, "stat output after moved source and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> stat /etc/user-copy.txt")
                val outputIndex =
                    terminal.indexOf("ERR NOENT /etc/user-copy.txt", startIndex = commandIndex + "K16> stat /etc/user-copy.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cat /etc/user-moved.txt\n")
            waitForTerminal(device, "cat output for moved file and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> cat /etc/user-moved.txt")
                val outputIndex = terminal.indexOf("hello-world", startIndex = commandIndex + "K16> cat /etc/user-moved.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "mv /etc/user.txt /etc/user-moved.txt\n")
            waitForTerminal(device, "mv existing destination error and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> mv /etc/user.txt /etc/user-moved.txt")
                val outputIndex =
                    terminal.indexOf(
                        "ERR INVAL /etc/user-moved.txt",
                        startIndex = commandIndex + "K16> mv /etc/user.txt /etc/user-moved.txt".length,
                    )
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "mv /etc/missing.txt /etc/missing-moved.txt\n")
            waitForTerminal(device, "mv missing source error and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> mv /etc/missing.txt /etc/missing-moved.txt")
                val outputIndex =
                    terminal.indexOf(
                        "ERR NOENT /etc/missing.txt",
                        startIndex = commandIndex + "K16> mv /etc/missing.txt /etc/missing-moved.txt".length,
                    )
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "mkdir /etc/mv-dir\n")
            waitForTerminal(device, "mkdir output for mv directory source and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> mkdir /etc/mv-dir")
                val outputIndex = terminal.indexOf("CREATED /etc/mv-dir", startIndex = commandIndex + "K16> mkdir /etc/mv-dir".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "mv /etc/mv-dir /etc/mv-dir2\n")
            waitForTerminal(device, "mv directory source error and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> mv /etc/mv-dir /etc/mv-dir2")
                val outputIndex =
                    terminal.indexOf(
                        "ERR INVAL /etc/mv-dir",
                        startIndex = commandIndex + "K16> mv /etc/mv-dir /etc/mv-dir2".length,
                    )
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "rmdir /etc/mv-dir\n")
            waitForTerminal(device, "rmdir cleanup for mv directory source and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> rmdir /etc/mv-dir")
                val outputIndex = terminal.indexOf("REMOVED /etc/mv-dir", startIndex = commandIndex + "K16> rmdir /etc/mv-dir".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            repeat(20) {
                val growPayload = "${it % 10}" + "x".repeat(29)
                dispatchText(device, "write --append /etc/user.txt $growPayload\n")
                waitForTerminal(device, "append write ${it + 1} output and returned prompt", attempts = 400) { terminal ->
                    val commandIndex = terminal.indexOf("K16> write --append /etc/user.txt $growPayload")
                    val outputIndex =
                        terminal.indexOf("WROTE 30 /etc/user.txt", startIndex = commandIndex + "K16> write --append /etc/user.txt $growPayload".length)
                    val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                    commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
                }
            }
            dispatchText(device, "stat /etc/user.txt\n")
            waitForTerminal(device, "stat output for grown appended file and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> stat /etc/user.txt")
                val outputIndex =
                    terminal.indexOf("FILE 611 /etc/user.txt", startIndex = commandIndex + "K16> stat /etc/user.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "rm /etc/user.txt\n")
            waitForTerminal(device, "rm output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> rm /etc/user.txt")
                val outputIndex = terminal.indexOf("REMOVED /etc/user.txt", startIndex = commandIndex + "K16> rm /etc/user.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "stat /etc/user.txt\n")
            waitForTerminal(device, "stat output after removed file and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> stat /etc/user.txt")
                val outputIndex =
                    terminal.indexOf("ERR NOENT /etc/user.txt", startIndex = commandIndex + "K16> stat /etc/user.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "mkdir /etc/user\n")
            waitForTerminal(device, "mkdir output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> mkdir /etc/user")
                val outputIndex = terminal.indexOf("CREATED /etc/user", startIndex = commandIndex + "K16> mkdir /etc/user".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "stat /etc/user\n")
            waitForTerminal(device, "stat output for created directory and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> stat /etc/user")
                val outputIndex = terminal.indexOf("DIR ", startIndex = commandIndex + "K16> stat /etc/user".length)
                val pathIndex = terminal.indexOf("/etc/user", startIndex = outputIndex)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = pathIndex)
                commandIndex >= 0 && outputIndex > commandIndex && pathIndex > outputIndex && returnedPromptIndex > pathIndex
            }

            dispatchText(device, "write /etc/user/file.txt data\n")
            waitForTerminal(device, "nested file write output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> write /etc/user/file.txt data")
                val outputIndex =
                    terminal.indexOf("WROTE 4 /etc/user/file.txt", startIndex = commandIndex + "K16> write /etc/user/file.txt data".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "rmdir /etc/user\n")
            waitForTerminal(device, "rmdir non-empty directory error and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> rmdir /etc/user")
                val outputIndex = terminal.indexOf("ERR NOTEMPTY /etc/user", startIndex = commandIndex + "K16> rmdir /etc/user".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "rm /etc/user/file.txt\n")
            waitForTerminal(device, "nested file rm output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> rm /etc/user/file.txt")
                val outputIndex =
                    terminal.indexOf("REMOVED /etc/user/file.txt", startIndex = commandIndex + "K16> rm /etc/user/file.txt".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "rmdir /etc/user\n")
            waitForTerminal(device, "rmdir empty directory output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> rmdir /etc/user")
                val outputIndex = terminal.indexOf("REMOVED /etc/user", startIndex = commandIndex + "K16> rmdir /etc/user".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "stat /etc/user\n")
            waitForTerminal(device, "stat output after removed directory and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> stat /etc/user")
                val outputIndex =
                    terminal.indexOf("ERR NOENT /etc/user", startIndex = commandIndex + "K16> stat /etc/user".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceRunsWriteSideFilesystemWorkflow() {
        val device = createDevice(deviceId = 244)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            var cursor = 0
            cursor = runShellCommandAndWait(device, cursor, "cd etc", "cd into etc returns prompt")
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "write workflow.txt alpha",
                    "relative write creates file in cwd",
                    "WROTE 5 /etc/workflow.txt",
                )
            cursor = runShellCommandAndWait(device, cursor, "status", "write success is remembered", "STATUS 0")
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "write --append workflow.txt -beta",
                    "relative append writes to file in cwd",
                    "WROTE 5 /etc/workflow.txt",
                )
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "cat workflow.txt",
                    "relative cat reads appended file",
                    "alpha-beta",
                )
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "cp workflow.txt workflow.copy",
                    "relative cp copies written file",
                    "COPIED /etc/workflow.txt /etc/workflow.copy",
                )
            cursor = runShellCommandAndWait(device, cursor, "status", "cp success is remembered", "STATUS 0")
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "mv workflow.copy workflow.moved",
                    "relative mv moves copied file",
                    "MOVED /etc/workflow.copy /etc/workflow.moved",
                )
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "cat workflow.moved",
                    "relative cat reads moved file",
                    "alpha-beta",
                )
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "stat workflow.copy",
                    "relative stat reports moved source missing",
                    "ERR NOENT /etc/workflow.copy",
                    "ERR EXIT 1",
                )
            cursor = runShellCommandAndWait(device, cursor, "status", "stat child failure is remembered", "STATUS 1")
            cursor =
                runShellCommandAndWait(
                    device,
                    cursor,
                    "rm workflow.moved workflow.txt",
                    "relative rm removes workflow files",
                    "REMOVED /etc/workflow.moved",
                    "REMOVED /etc/workflow.txt",
                )
            runShellCommandAndWait(device, cursor, "status", "rm success resets status", "STATUS 0")
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDevicePersistsShellWrittenFileAcrossStorageRestart() {
        val workspace = createTempDirectory("k16-shell-storage-restart-smoke-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        val first = createDeviceWithExistingStorage(deviceId = 245, biosFlashPath = biosFlashPath, storage0Path = storage0Path)
        try {
            first.turnOn()
            waitForTerminalText(first, "K16> ")

            var cursor = 0
            cursor =
                runShellCommandAndWait(
                    first,
                    cursor,
                    "write /etc/persist.txt alpha",
                    "write creates persistent file before restart",
                    "WROTE 5 /etc/persist.txt",
                )
            runShellCommandAndWait(
                first,
                cursor,
                "cat /etc/persist.txt",
                "written file is readable before restart",
                "alpha",
            )
        } finally {
            first.close()
        }

        val second = createDeviceWithExistingStorage(deviceId = 246, biosFlashPath = biosFlashPath, storage0Path = storage0Path)
        try {
            second.turnOn()
            waitForTerminalText(second, "K16> ")

            var cursor = 0
            cursor =
                runShellCommandAndWait(
                    second,
                    cursor,
                    "cat /etc/persist.txt",
                    "written file is readable after fresh boot with same storage0",
                    "alpha",
                )
            runShellCommandAndWait(second, cursor, "status", "cat after storage restart reports success", "STATUS 0")
        } finally {
            second.close()
        }
    }

    @Test
    fun runtimeDeviceGrowsUserDirectoryThroughUserlandShell() {
        val device = createDevice(deviceId = 236)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchText(device, "mkdir /etc/grow\n")
            waitForTerminal(device, "mkdir grow output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> mkdir /etc/grow")
                val outputIndex = terminal.indexOf("CREATED /etc/grow", startIndex = commandIndex + "K16> mkdir /etc/grow".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            repeat(9) { index ->
                val path = "/etc/grow/f$index"
                dispatchText(device, "write $path x\n")
                waitForTerminal(device, "write output for $path and returned prompt") { terminal ->
                    val commandIndex = terminal.lastPhysicalIndexOf("K16> write $path x")
                    val outputIndex = terminal.indexOf("WROTE 1 $path", startIndex = commandIndex + "K16> write $path x".length)
                    val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                    commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
                }
            }

            dispatchText(device, "ls /etc/grow\n")
            waitForTerminal(device, "ls output includes first and ninth grown directory entries") { terminal ->
                val commandIndex = terminal.indexOf("K16> ls /etc/grow")
                val firstIndex = terminal.indexOf("f0", startIndex = commandIndex + "K16> ls /etc/grow".length)
                val ninthIndex = terminal.indexOf("f8", startIndex = firstIndex)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = ninthIndex)
                commandIndex >= 0 && firstIndex > commandIndex && ninthIndex > firstIndex && returnedPromptIndex > ninthIndex
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceGrowsRegularFileThroughUserlandShell() {
        val device = createDevice(deviceId = 237)
        val chunkA = "a".repeat(128)
        val chunkB = "b".repeat(128)
        val chunkC = "c".repeat(128)
        val chunkD = "d".repeat(128)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchPasteText(device, "write /etc/grow-file $chunkA\n")
            waitForTerminal(device, "initial grow-file write output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> write /etc/grow-file $chunkA")
                val outputIndex =
                    terminal.indexOf("WROTE 128 /etc/grow-file", startIndex = commandIndex + "K16> write /etc/grow-file $chunkA".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            listOf(chunkB, chunkC, chunkD).forEach { chunk ->
                dispatchPasteText(device, "write --append /etc/grow-file $chunk\n")
                waitForTerminal(device, "append grow-file chunk output and returned prompt") { terminal ->
                    val commandIndex = terminal.lastPhysicalIndexOf("K16> write --append /etc/grow-file $chunk")
                    val outputIndex =
                        terminal.indexOf(
                            "WROTE 128 /etc/grow-file",
                            startIndex = commandIndex + "K16> write --append /etc/grow-file $chunk".length,
                        )
                    val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                    commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
                }
            }

            dispatchText(device, "write /etc/grow-blocker x\n")
            waitForTerminal(device, "blocker write output and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> write /etc/grow-blocker x")
                val outputIndex =
                    terminal.indexOf("WROTE 1 /etc/grow-blocker", startIndex = commandIndex + "K16> write /etc/grow-blocker x".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "write --append /etc/grow-file z\n")
            waitForTerminal(device, "append grow-file past blocked adjacent block and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> write --append /etc/grow-file z")
                val outputIndex =
                    terminal.indexOf("WROTE 1 /etc/grow-file", startIndex = commandIndex + "K16> write --append /etc/grow-file z".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "stat /etc/grow-file\n")
            waitForTerminal(device, "stat output for multi-extent grown file and returned prompt") { terminal ->
                val commandIndex = terminal.lastPhysicalIndexOf("K16> stat /etc/grow-file")
                val outputIndex = terminal.indexOf("FILE 513 /etc/grow-file", startIndex = commandIndex + "K16> stat /etc/grow-file".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }

            dispatchText(device, "cat /etc/grow-file\n")
            waitForTerminal(device, "cat output includes bytes across the new inline extent") { terminal ->
                val commandIndex = terminal.indexOf("K16> cat /etc/grow-file")
                val outputIndex = terminal.indexOf("dz", startIndex = commandIndex + "K16> cat /etc/grow-file".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
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

    @Test
    fun runtimeDeviceRunsBoundedYesThroughUserlandShell() {
        val device = createDevice(deviceId = 229)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchText(device, "yes -n 3 hi\n")
            waitForTerminal(device, "bounded yes output and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> yes -n 3 hi")
                if (commandIndex < 0) return@waitForTerminal false
                val first = terminal.indexOf("hi", startIndex = commandIndex + "K16> yes -n 3 hi".length)
                if (first < 0) return@waitForTerminal false
                val second = terminal.indexOf("hi", startIndex = first + "hi".length)
                if (second < 0) return@waitForTerminal false
                val third = terminal.indexOf("hi", startIndex = second + "hi".length)
                if (third < 0) return@waitForTerminal false
                val prompt = terminal.indexOf("K16> ", startIndex = third + "hi".length)
                prompt > third
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceShellExecFailsWhenProgramIsMissingFromRootFilesystem() {
        val device =
            createDevice(deviceId = 232) { storage0Path ->
                val root = storage0Path.parent.resolve("root.kfs")
                runK16Tool(
                    "volume",
                    "extract-partition",
                    storage0Path.toString(),
                    "ROOT",
                    root.toString(),
                )
                runK16Tool(
                    "fs",
                    "kfs",
                    "rm",
                    root.toString(),
                    "/bin/uname.kx",
                )
                runK16Tool(
                    "volume",
                    "replace-partition",
                    storage0Path.toString(),
                    "ROOT",
                    root.toString(),
                )
            }

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchText(device, "uname\n")
            waitForTerminal(device, "missing storage-backed uname executable reports no entry") { terminal ->
                val commandIndex = terminal.indexOf("K16> uname")
                val outputIndex = terminal.indexOf("ERR NOENT", startIndex = commandIndex + "K16> uname".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun runtimeDeviceRebootsAfterNestedShellReportsBusy() {
        val device = createDevice(deviceId = 229)

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")

            dispatchText(device, "shell\n")
            waitForTerminal(device, "nested shell prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> shell")
                val nestedBannerIndex = terminal.indexOf("K16 SHELL", startIndex = commandIndex + "K16> shell".length)
                val nestedPromptIndex = terminal.indexOf("K16> ", startIndex = nestedBannerIndex + "K16 SHELL".length)
                commandIndex >= 0 && nestedBannerIndex > commandIndex && nestedPromptIndex > nestedBannerIndex
            }

            dispatchText(device, "shell\n")
            waitForTerminal(device, "second nested shell prompt") { terminal ->
                val firstCommandIndex = terminal.indexOf("K16> shell")
                val secondCommandIndex =
                    terminal.indexOf("K16> shell", startIndex = firstCommandIndex + "K16> shell".length)
                val secondBannerIndex = terminal.indexOf("K16 SHELL", startIndex = secondCommandIndex + "K16> shell".length)
                val secondPromptIndex = terminal.indexOf("K16> ", startIndex = secondBannerIndex + "K16 SHELL".length)
                secondCommandIndex >= 0 && secondBannerIndex > secondCommandIndex && secondPromptIndex > secondBannerIndex
            }

            dispatchText(device, "shell\n")
            waitForTerminal(device, "nested shell busy error") { terminal ->
                val firstCommandIndex = terminal.indexOf("K16> shell")
                val secondCommandIndex =
                    terminal.indexOf("K16> shell", startIndex = firstCommandIndex + "K16> shell".length)
                val busyCommandIndex =
                    terminal.indexOf("K16> shell", startIndex = secondCommandIndex + "K16> shell".length)
                val busyIndex = terminal.indexOf("ERR BUSY", startIndex = busyCommandIndex + "K16> shell".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = busyIndex + "ERR BUSY".length)
                busyCommandIndex >= 0 && busyIndex > busyCommandIndex && returnedPromptIndex > busyIndex
            }

            device.reboot()

            waitForTerminal(device, "fresh shell prompt after nested-shell reboot") { terminal ->
                terminal.contains("K16 SHELL") && terminal.contains("K16> ")
            }
        } finally {
            device.close()
        }
    }

    private fun createDevice(
        deviceId: Int,
        storageResourcePath: String = "firmware/k16-system-storage0.kv",
        configureStorage0: (Path) -> Unit = {},
    ): K16RuntimeDevice {
        val workspace = createTempDirectory("k16-shell-runtime-smoke-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = storageResourcePath,
                classLoader = javaClass.classLoader,
            ),
        )
        configureStorage0(storage0Path)

        return createDeviceWithExistingStorage(
            deviceId = deviceId,
            biosFlashPath = biosFlashPath,
            storage0Path = storage0Path,
        )
    }

    private fun createDeviceWithExistingStorage(
        deviceId: Int,
        biosFlashPath: Path,
        storage0Path: Path,
    ): K16RuntimeDevice {
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
        attempts: Int = 400,
        predicate: (String) -> Boolean,
    ) {
        repeat(attempts) {
            tickAndSync(device)
            val snapshot = device.snapshotRuntimeState()
            if (snapshot != null && predicate(terminalText(snapshot))) return
            Thread.sleep(10)
        }
        val snapshot = device.snapshotRuntimeState()
        val terminal = snapshot?.let(::terminalText) ?: "<no snapshot>"
        val cpu = snapshot?.let(::snapshotCpuText) ?: "<no snapshot>"
        error("K16 shell runtime smoke did not observe $description; cpu: $cpu; terminal: $terminal")
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

    private fun runShellCommandAndWait(
        device: K16RuntimeDevice,
        cursor: Int,
        command: String,
        description: String,
        vararg fragments: String,
    ): Int {
        dispatchText(device, "$command\n")
        var observedCursor = cursor
        waitForTerminal(device, description, attempts = 400) { terminal ->
            val commandFragment = "K16> $command"
            val searchCursor = if (cursor >= terminal.length) 0 else cursor
            var nextCursor = terminal.indexOf(commandFragment, startIndex = searchCursor)
            if (nextCursor < 0 && searchCursor > 0) nextCursor = terminal.indexOf(commandFragment)
            if (nextCursor < 0) return@waitForTerminal false
            nextCursor += commandFragment.length
            for (fragment in fragments) {
                val index = terminal.indexOf(fragment, startIndex = nextCursor)
                if (index < 0) return@waitForTerminal false
                nextCursor = index + fragment.length
            }
            val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = nextCursor)
            if (returnedPromptIndex < 0) return@waitForTerminal false
            observedCursor = returnedPromptIndex
            true
        }
        return observedCursor
    }

    private fun tickAndSync(device: K16RuntimeDevice) {
        device.serverTick()
        device.snapshotRuntimeState()
    }

    private fun terminalText(snapshot: ByteArray): String {
        val physicalRows =
            snapshotRamBytes(snapshot, start = K16_TERMINAL_CELLS_ADDR, size = K16_TERMINAL_ROWS * K16_TERMINAL_COLUMNS)
                .map { byte -> if (byte in 0x20..0x7e) byte.toInt().toChar() else ' ' }
                .joinToString(separator = "")
        return physicalRows + physicalRows
    }

    private fun String.lastPhysicalIndexOf(fragment: String): Int =
        lastIndexOf(fragment, startIndex = length / 2 - 1)

    private fun snapshotCpuText(snapshot: ByteArray): String {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        require(snapshot.copyOfRange(0, 8).contentEquals("K16SNAP\u0000".encodeToByteArray()))
        val ramSize = buffer.getLong(0x10).toInt()
        val cpuOffset = 40 + ramSize
        val kind = buffer.getInt(cpuOffset)
        if (kind != 1) return "kind=$kind"
        val state = buffer.getInt(cpuOffset + 4)
        val pc = buffer.getInt(cpuOffset + 16)
        val trapCause = buffer.getInt(cpuOffset + 24)
        val trapPc = buffer.getInt(cpuOffset + 28)
        val trapValue = buffer.getInt(cpuOffset + 32)
        val trapStackPointer = buffer.getInt(cpuOffset + 52)
        val registersOffset = cpuOffset + 56
        val r0 = buffer.getInt(registersOffset)
        val r1 = buffer.getInt(registersOffset + 4)
        val r2 = buffer.getInt(registersOffset + 8)
        val r3 = buffer.getInt(registersOffset + 12)
        val sp = buffer.getInt(registersOffset + 14 * 4)
        val trapArg0 = buffer.getInt(cpuOffset + 128)
        val trapArg1 = buffer.getInt(cpuOffset + 132)
        val trapArg2 = buffer.getInt(cpuOffset + 136)
        return "state=$state pc=${pc.hex32()} trapCause=${trapCause.hex32()} trapPc=${trapPc.hex32()} " +
            "trapValue=${trapValue.hex32()} trapArgs=${trapArg0.hex32()},${trapArg1.hex32()},${trapArg2.hex32()} " +
            "r0=${r0.hex32()} r1=${r1.hex32()} r2=${r2.hex32()} r3=${r3.hex32()} " +
            "sp=${sp.hex32()} trapSp=${trapStackPointer.hex32()}"
    }

    private fun Int.hex32(): String = "0x" + toUInt().toString(16).padStart(8, '0')

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

    private fun runK16Tool(vararg args: String) {
        val executable = k16ToolExecutable()
        assertTrue(Files.isExecutable(executable), "K16 tool should be executable at $executable")
        val process =
            ProcessBuilder(listOf(executable.toString()) + args.toList())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        val exitCode = process.waitFor()
        assertTrue(exitCode == 0, "k16 ${args.joinToString(" ")} failed:\n$output")
    }

    private fun k16ToolExecutable(): Path {
        return Path.of("../../../.toolchain/build/cargo/k16-tools/release/k16")
    }
}

private const val K16_TERMINAL_CELLS_ADDR = 0x3000
private const val K16_TERMINAL_COLUMNS = 64
private const val K16_TERMINAL_ROWS = 25
