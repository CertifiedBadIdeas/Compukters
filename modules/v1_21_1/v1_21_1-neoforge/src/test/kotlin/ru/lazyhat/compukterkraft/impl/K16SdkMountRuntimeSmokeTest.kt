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
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16StaticStorageAttachment
import ru.lazyhat.compukterkraft.lang.runtime.kraftos.K16SdkArtifacts
import ru.lazyhat.compukterkraft.lang.runtime.kraftos.KraftOsArtifactManifest
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16ImmutableArtifactWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals

class K16SdkMountRuntimeSmokeTest {
    @Test
    fun sdkFixtureMountIsReadableExecutableAndReadOnlyAlongsideStorage0() {
        val workspace = createTempDirectory("k16-sdk-mount-runtime-smoke-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = "firmware/k16-system-storage0-dev.kv",
                classLoader = javaClass.classLoader,
            ),
        )
        val sdkPath =
            K16SdkArtifacts(
                manifest = KraftOsArtifactManifest.load(classLoader = javaClass.classLoader),
                workspace = K16ImmutableArtifactWorkspace(workspace),
                classLoader = javaClass.classLoader,
            ).resolve(SDK_FIXTURE_IDENTITY)
        val sdkDigestBefore = sha256(sdkPath.readBytes())

        val device =
            K16RuntimeDevice(
                deviceId = 467,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "sdk-mount-smoke"),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                        storage1 = K16StaticStorageAttachment(sdkPath),
                    )
                },
                stateSink = {},
                serverThreadDispatcher = directServerThreadDispatcher,
            )

        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ")
            assertCommandResult(device, "cat /sdk/fixture.txt", "sdk fixture")
            assertCommandResult(device, "cat /etc/motd", "K16 FS OK")
            assertCommandResult(device, "cat /sdk/fixture.txt", "sdk fixture")
            assertCommandResult(device, "/sdk/bin/uname.kx", "K16")
            assertCommandResult(device, "write /sdk/blocked.txt nope", "ERR ROFS /sdk/blocked.txt")
        } finally {
            device.close()
        }

        assertContentEquals(sdkDigestBefore, sha256(sdkPath.readBytes()))
    }

    private fun assertCommandResult(
        device: K16RuntimeDevice,
        command: String,
        expectedOutput: String,
    ) {
        dispatchText(device, "$command\n")
        waitForTerminal(device, "'$expectedOutput' followed by a returned prompt for '$command'") { terminal ->
            val commandIndex = terminal.lastIndexOf("K16> $command")
            val outputIndex = terminal.indexOf(expectedOutput, startIndex = commandIndex + command.length)
            val promptIndex = terminal.indexOf("K16> ", startIndex = outputIndex + expectedOutput.length)
            commandIndex >= 0 && outputIndex > commandIndex && promptIndex > outputIndex
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
        error("K16 SDK mount runtime smoke did not observe $description; terminal: $terminal")
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

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

private const val SDK_FIXTURE_IDENTITY = "sdk_fixture_v1"
private const val K16_TERMINAL_CELLS_ADDR = 0x3000
private const val K16_TERMINAL_COLUMNS = 64
private const val K16_TERMINAL_ROWS = 25
