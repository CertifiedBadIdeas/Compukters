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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertTrue

class K16TinyCcRuntimeSmokeTest {
    @Test
    fun tinyCcBuiltUnameRunsInsideKraftOs() {
        val tinyCcUname =
            System.getProperty("k16.tinycc.uname.path")
                ?.let(Path::of)
                ?: error("k16.tinycc.uname.path must point to the TinyCC-built uname proof")
        assertTrue(
            Files.isRegularFile(tinyCcUname),
            "TinyCC uname proof should be a regular file at $tinyCcUname",
        )

        val workspace = createTempDirectory("k16-tinycc-runtime-smoke-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        val rootPath = workspace.resolve("root.kfs")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = "firmware/k16-system-storage0.kv",
                classLoader = javaClass.classLoader,
            ),
        )

        runK16Tool(
            "volume",
            "extract-partition",
            storage0Path.toString(),
            "ROOT",
            rootPath.toString(),
        )
        runK16Tool("fs", "kfs", "rm", rootPath.toString(), "/bin/uname.kx")
        runK16Tool("fs", "kfs", "put", rootPath.toString(), "/bin/uname.kx", tinyCcUname.toString())
        runK16Tool("volume", "replace-partition", storage0Path.toString(), "ROOT", rootPath.toString())

        val device =
            K16RuntimeDevice(
                deviceId = 464,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "tinycc-uname-smoke"),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                    )
                },
                stateSink = {},
                serverThreadDispatcher = directServerThreadDispatcher,
            )

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")
            dispatchText(device, "uname\n")
            waitForTerminal(device, "TinyCC uname output and returned prompt") { terminal ->
                val commandIndex = terminal.indexOf("K16> uname")
                val outputIndex = terminal.indexOf("K16", startIndex = commandIndex + "K16> uname".length)
                val returnedPromptIndex = terminal.indexOf("K16> ", startIndex = outputIndex + "K16".length)
                commandIndex >= 0 && outputIndex > commandIndex && returnedPromptIndex > outputIndex
            }
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
        val terminal = device.snapshotRuntimeState()?.let(::terminalText) ?: "<no snapshot>"
        error("K16 TinyCC runtime smoke did not observe $description; terminal: $terminal")
    }

    private fun tickAndSync(device: K16RuntimeDevice) {
        device.serverTick()
        device.snapshotRuntimeState()
    }

    private fun terminalText(snapshot: ByteArray): String {
        val physicalRows =
            snapshotRamBytes(
                snapshot,
                start = K16_TERMINAL_CELLS_ADDR,
                size = K16_TERMINAL_ROWS * K16_TERMINAL_COLUMNS,
            ).map { byte -> if (byte in 0x20..0x7e) byte.toInt().toChar() else ' ' }
                .joinToString(separator = "")
        return physicalRows + physicalRows
    }

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
        val executable = Path.of("../../../.toolchain/build/cargo/k16-tools/release/k16")
        assertTrue(Files.isExecutable(executable), "K16 tool should be executable at $executable")
        val process =
            ProcessBuilder(listOf(executable.toString()) + args.toList())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        val exitCode = process.waitFor()
        assertTrue(exitCode == 0, "k16 ${args.joinToString(" ")} failed:\n$output")
    }
}

private const val K16_TERMINAL_CELLS_ADDR = 0x3000
private const val K16_TERMINAL_COLUMNS = 64
private const val K16_TERMINAL_ROWS = 25
