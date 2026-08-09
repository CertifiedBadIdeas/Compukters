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
import ru.lazyhat.compukterkraft.core.device.runtime.NoOpRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.vm.DeviceProfileRegistry
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16StaticStorageAttachment
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class K16NativeTinyCcCompileTest {
    @Test
    fun normalNotebookRejectsNativeTinyCcCompilationWithoutCreatingObject() {
        val candidatePath = requiredPathProperty("k16.native.tinycc.candidate.path")
        val k16Tool = requiredExecutableProperty("k16.native.tinycc.tool.path")
        val candidateDigestBefore = sha256(candidatePath.readBytes())
        val workspace = createTempDirectory("k16-native-tinycc-normal-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        val rootBefore = workspace.resolve("root-before.kfs")
        val rootAfter = workspace.resolve("root-after.kfs")
        val helloSource = workspace.resolve("hello.c")
        val missingObject = workspace.resolve("missing-hello.o")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = "firmware/k16-system-storage0-dev.kv",
                classLoader = javaClass.classLoader,
            ),
        )
        helloSource.writeBytes(
            javaClass
                .getResourceAsStream("/k16-native-tinycc/hello.c")
                ?.use { it.readBytes() }
                ?: error("Missing native TinyCC acceptance source"),
        )
        runK16Tool(
            k16Tool,
            "volume",
            "extract-partition",
            storage0Path.toString(),
            "ROOT",
            rootBefore.toString(),
        )
        runK16Tool(k16Tool, "fs", "kfs", "mkdir", rootBefore.toString(), "/work")
        runK16Tool(
            k16Tool,
            "fs",
            "kfs",
            "put",
            rootBefore.toString(),
            "/work/hello.c",
            helloSource.toString(),
        )
        runK16Tool(
            k16Tool,
            "volume",
            "replace-partition",
            storage0Path.toString(),
            "ROOT",
            rootBefore.toString(),
        )

        val device =
            createDevice(
                deviceId = 465,
                label = "normal-native-tinycc-rejection",
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
                storage1 = K16StaticStorageAttachment(candidatePath),
                family = DeviceFamily.NORMAL,
            )
        try {
            device.turnOn()
            waitForTerminalText(device, "K16> ", "normal notebook shell prompt")
            val output =
                assertCommandReturnsPrompt(
                    device,
                    "/sdk/bin/tcc.kx -c /work/hello.c -o /work/hello.o",
                    "normal notebook native compiler rejection",
                )
            val normalizedOutput = output.normalizedWords().lowercase()
            println("k16NormalTinyCcRejection: output=${output.normalizedWords()}")
            assertTrue(
                normalizedOutput.contains("err nomem") || normalizedOutput.contains("tcc: memory full"),
                "Normal Notebook must fail through loader or TinyCC allocation: $output",
            )
            assertLastStatusNonZero(device, "normal notebook native compiler rejection")
            assertTrue(device.runtimeFailureMessage == null, device.runtimeFailureMessage)
            device.shutdown()
            assertFalse(device.isOn, "normal notebook shutdown should complete")
        } finally {
            device.close()
        }

        assertContentEquals(candidateDigestBefore, sha256(candidatePath.readBytes()))
        runK16Tool(
            k16Tool,
            "volume",
            "extract-partition",
            storage0Path.toString(),
            "ROOT",
            rootAfter.toString(),
        )
        val getObject =
            runProcess(
                listOf(
                    k16Tool.toString(),
                    "fs",
                    "kfs",
                    "get",
                    rootAfter.toString(),
                    "/work/hello.o",
                    missingObject.toString(),
                ),
            )
        assertTrue(getObject.exitCode != 0 && !Files.exists(missingObject), "/work/hello.o must not exist")
    }

    @Test
    fun nativeTinyCcCompilesGuestSourceBeforeHostValidationAndExecution() {
        val candidatePath = requiredPathProperty("k16.native.tinycc.candidate.path")
        val libkraftPath = requiredPathProperty("k16.native.tinycc.libkraft.path")
        val k16Tool = requiredExecutableProperty("k16.native.tinycc.tool.path")
        val llvmReadObj = requiredExecutableProperty("k16.native.tinycc.llvm.readobj.path")
        val compilerPath = candidatePath.resolveSibling("tcc.kx")
        assertTrue(Files.isRegularFile(compilerPath), "native TinyCC artifact must exist: $compilerPath")
        val candidateDigestBefore = sha256(candidatePath.readBytes())
        val workspace = createTempDirectory("k16-native-tinycc-compile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val compilerStorage0 = workspace.resolve("compiler-storage0.kv")
        val compilerRootBefore = workspace.resolve("compiler-root-before.kfs")
        val helloSource = workspace.resolve("hello.c")
        val missingObject = workspace.resolve("missing-hello.o")

        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        compilerStorage0.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = "firmware/k16-system-storage0-dev.kv",
                classLoader = javaClass.classLoader,
            ),
        )
        helloSource.writeBytes(
            javaClass
                .getResourceAsStream("/k16-native-tinycc/hello.c")
                ?.use { it.readBytes() }
                ?: error("Missing native TinyCC acceptance source"),
        )
        runK16Tool(
            k16Tool,
            "volume",
            "extract-partition",
            compilerStorage0.toString(),
            "ROOT",
            compilerRootBefore.toString(),
        )
        runK16Tool(k16Tool, "fs", "kfs", "mkdir", compilerRootBefore.toString(), "/work")
        runK16Tool(
            k16Tool,
            "fs",
            "kfs",
            "put",
            compilerRootBefore.toString(),
            "/work/hello.c",
            helloSource.toString(),
        )
        val preexistingObject =
            runProcess(
                listOf(
                    k16Tool.toString(),
                    "fs",
                    "kfs",
                    "get",
                    compilerRootBefore.toString(),
                    "/work/hello.o",
                    missingObject.toString(),
                ),
            )
        assertTrue(
            preexistingObject.exitCode != 0 && !Files.exists(missingObject),
            "/work/hello.o did not exist before launch",
        )
        runK16Tool(
            k16Tool,
            "volume",
            "replace-partition",
            compilerStorage0.toString(),
            "ROOT",
            compilerRootBefore.toString(),
        )

        val compilerMetrics = RecordingRuntimeMetricsCollector()
        val compilerDevice =
            createDevice(
                deviceId = 466,
                label = "native-tinycc-compiler",
                biosFlashPath = biosFlashPath,
                storage0Path = compilerStorage0,
                storage1 = K16StaticStorageAttachment(candidatePath),
                metrics = compilerMetrics,
            )
        try {
            compilerDevice.turnOn()
            waitForTerminalText(compilerDevice, "K16> ", "compiler shell prompt")
            val beforeCompile = compilerMetrics.snapshot().k16
            val compileStartedAt = System.nanoTime()
            assertCommandReturnsPrompt(
                compilerDevice,
                "/sdk/bin/tcc.kx -c /work/hello.c -o /work/hello.o",
                "native compiler exit",
            )
            val compileDuration = Duration.ofNanos(System.nanoTime() - compileStartedAt)
            val afterCompile = compilerMetrics.snapshot().k16
            val compileCpuSteps = afterCompile.cpuSteps - beforeCompile.cpuSteps
            val compileGameTicks = afterCompile.gameTicks - beforeCompile.gameTicks
            val compileHeapPages = afterCompile.os.lastExitedProgramHeapPages
            val compileRow =
                "k16NativeTinyCcCompile: tccBytes=${Files.size(compilerPath)}, " +
                    "cpuSteps=$compileCpuSteps, gameTicks=$compileGameTicks, " +
                    "hostDuration=${compileDuration.toNanos()} ns, heapPages=$compileHeapPages"
            println(compileRow)
            assertTrue(Files.size(compilerPath) > 0, compileRow)
            assertTrue(compileCpuSteps > 0, compileRow)
            assertTrue(compileGameTicks > 0, compileRow)
            assertTrue(compileDuration > Duration.ZERO, compileRow)
            assertTrue(compileHeapPages > 0, compileRow)
            assertLastStatusZero(compilerDevice, "native compiler")
            assertCommandOutput(
                compilerDevice,
                "write /sdk/blocked.txt nope",
                "ERR ROFS /sdk/blocked.txt\nERR EXIT 1",
            )
            assertTrue(compilerDevice.runtimeFailureMessage == null, compilerDevice.runtimeFailureMessage)
            compilerDevice.shutdown()
            assertFalse(compilerDevice.isOn, "native compiler shutdown should complete")
        } finally {
            compilerDevice.close()
        }
        assertContentEquals(candidateDigestBefore, sha256(candidatePath.readBytes()))

        val compilerRootAfter = workspace.resolve("compiler-root-after.kfs")
        val helloObject = workspace.resolve("hello.o")
        runK16Tool(
            k16Tool,
            "volume",
            "extract-partition",
            compilerStorage0.toString(),
            "ROOT",
            compilerRootAfter.toString(),
        )
        runK16Tool(
            k16Tool,
            "fs",
            "kfs",
            "get",
            compilerRootAfter.toString(),
            "/work/hello.o",
            helloObject.toString(),
        )
        validateGuestObject(llvmReadObj, helloObject)

        val extractedSdk = workspace.resolve("extracted-sdk")
        val sdkRoot = extractedSdk.resolve("root.kfs")
        extractedSdk.createDirectories()
        runK16Tool(k16Tool, "volume", "extract-partition", candidatePath.toString(), "ROOT", sdkRoot.toString())
        val crt0 = extractSdkFile(k16Tool, sdkRoot, extractedSdk, "/sdk/lib/crt0.o")
        val libc = extractSdkFile(k16Tool, sdkRoot, extractedSdk, "/sdk/lib/libc.a")
        val softFloat = extractSdkFile(k16Tool, sdkRoot, extractedSdk, "/sdk/lib/libsoftfloat.a")
        val compilerRt = extractSdkFile(k16Tool, sdkRoot, extractedSdk, "/sdk/lib/libcompiler_rt.a")
        val startup = workspace.resolve("hello-startup.o")
        val helloImage = workspace.resolve("hello.kx")
        runK16Tool(
            k16Tool,
            "runtime",
            "k16-startup",
            "--target",
            "program-dynamic",
            "--entry",
            "kraft_start",
            "-o",
            startup.toString(),
        )
        runK16Tool(
            k16Tool,
            "link",
            "--target",
            "program-dynamic",
            "--dylib",
            libkraftPath.toString(),
            startup.toString(),
            crt0.toString(),
            helloObject.toString(),
            libc.toString(),
            softFloat.toString(),
            compilerRt.toString(),
            "-o",
            helloImage.toString(),
        )

        val executionStorage0 = workspace.resolve("execution-storage0.kv")
        val executionRoot = workspace.resolve("execution-root.kfs")
        executionStorage0.writeBytes(
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                resourcePath = "firmware/k16-system-storage0-dev.kv",
                classLoader = javaClass.classLoader,
            ),
        )
        runK16Tool(
            k16Tool,
            "volume",
            "extract-partition",
            executionStorage0.toString(),
            "ROOT",
            executionRoot.toString(),
        )
        runK16Tool(
            k16Tool,
            "fs",
            "kfs",
            "put",
            executionRoot.toString(),
            "/bin/native-hello.kx",
            helloImage.toString(),
        )
        runK16Tool(
            k16Tool,
            "volume",
            "replace-partition",
            executionStorage0.toString(),
            "ROOT",
            executionRoot.toString(),
        )

        val executionDevice =
            createDevice(
                deviceId = 467,
                label = "native-tinycc-output",
                biosFlashPath = biosFlashPath,
                storage0Path = executionStorage0,
            )
        try {
            executionDevice.turnOn()
            waitForTerminalText(executionDevice, "K16> ", "execution shell prompt")
            assertCommandOutput(executionDevice, "/bin/native-hello.kx", EXPECTED_OUTPUT.trimEnd())
            assertLastStatusZero(executionDevice, "native TinyCC output")
            assertTrue(executionDevice.runtimeFailureMessage == null, executionDevice.runtimeFailureMessage)
            executionDevice.shutdown()
            assertFalse(executionDevice.isOn, "linked program shutdown should complete")
        } finally {
            executionDevice.close()
        }
    }

    private fun createDevice(
        deviceId: Int,
        label: String,
        biosFlashPath: Path,
        storage0Path: Path,
        storage1: K16StaticStorageAttachment? = null,
        metrics: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
        family: DeviceFamily = DeviceFamily.ADVANCED,
    ): K16RuntimeDevice {
        val profile = DeviceProfileRegistry.forFamily(family)
        return K16RuntimeDevice(
            deviceId = deviceId,
            properties = DeviceProperties(family, label = label),
            endpointFactory = {
                K16ComputerRuntimeFactory.createFromBiosFlash(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                    storage1 = storage1,
                    memorySize = profile.resources.memory.vmRamBytes.toInt(),
                    maxSteps = profile.resources.cpu.maxStepsPerSlice,
                    maxTurnsPerTick = profile.resources.cpu.maxTurnsPerTick,
                )
            },
            stateSink = {},
            serverThreadDispatcher = directServerThreadDispatcher,
            metricsCollector = metrics,
        )
    }

    private fun validateGuestObject(
        llvmReadObj: Path,
        helloObject: Path,
    ) {
        val report =
            runProcess(
                listOf(
                    llvmReadObj.toString(),
                    "-h",
                    "-S",
                    "-s",
                    "-r",
                    helloObject.toString(),
                ),
            )
        assertTrue(report.exitCode == 0, "llvm-readobj failed:\n${report.output}")
        listOf(
            "Type: Relocatable (0x1)",
            "Machine: 0x5258",
            "Name: .text.k16",
            "Name: .data",
            "Name: .bss",
            "Name: global_bias",
            "Name: global_runs",
            "R_K16_CALL32 __mulsf3",
            "R_K16_CALL32 __floatsisf",
            "R_K16_CALL32 __fixsfsi",
        ).forEach { expected ->
            assertTrue(report.output.contains(expected), "missing '$expected' in:\n${report.output}")
        }
    }

    private fun extractSdkFile(
        k16Tool: Path,
        sdkRoot: Path,
        outputDirectory: Path,
        guestPath: String,
    ): Path {
        val output = outputDirectory.resolve(guestPath.substringAfterLast('/'))
        val storagePath = guestPath.removePrefix("/sdk")
        runK16Tool(k16Tool, "fs", "kfs", "get", sdkRoot.toString(), storagePath, output.toString())
        return output
    }

    private fun assertCommandReturnsPrompt(
        device: K16RuntimeDevice,
        command: String,
        description: String,
    ): String {
        dispatchText(device, "$command\n")
        val terminal = waitForTerminal(device, description) { snapshot -> commandOutput(snapshot, command) != null }
        return checkNotNull(commandOutput(terminal, command))
    }

    private fun assertLastStatusZero(
        device: K16RuntimeDevice,
        description: String,
    ) {
        val command = "status"
        dispatchText(device, "$command\n")
        val terminal =
            waitForTerminal(device, "$description status") { snapshot ->
                commandOutput(snapshot, command) != null
            }
        val status = commandOutput(terminal, command)?.normalizedWords()
        val cpu = device.snapshotRuntimeState()?.let(::snapshotCpuText) ?: "<no snapshot>"
        val debug = device.serialOutputSnapshot().decodeToString()
        assertTrue(status == "STATUS 0", "$terminal\nCPU: $cpu\nDebug: $debug")
    }

    private fun assertLastStatusNonZero(
        device: K16RuntimeDevice,
        description: String,
    ) {
        val command = "status"
        dispatchText(device, "$command\n")
        val terminal =
            waitForTerminal(device, "$description status") { snapshot ->
                commandOutput(snapshot, command) != null
            }
        val status = commandOutput(terminal, command)?.normalizedWords()
        val cpu = device.snapshotRuntimeState()?.let(::snapshotCpuText) ?: "<no snapshot>"
        val debug = device.serialOutputSnapshot().decodeToString()
        assertTrue(status != null && status != "STATUS 0", "$terminal\nCPU: $cpu\nDebug: $debug")
    }

    private fun assertCommandOutput(
        device: K16RuntimeDevice,
        command: String,
        expectedOutput: String,
    ) {
        dispatchText(device, "$command\n")
        val terminal =
            waitForTerminal(device, "'$expectedOutput' followed by a returned prompt for '$command'") { snapshot ->
                commandOutput(snapshot, command) != null
            }
        assertTrue(
            commandOutput(terminal, command)?.normalizedWords() == expectedOutput.normalizedWords(),
            terminal,
        )
    }

    private fun dispatchText(
        device: K16RuntimeDevice,
        text: String,
    ) {
        for (byte in text.encodeToByteArray()) {
            DeviceEvents.dispatch(device, KeyInputEvent.Character(byte))
        }
        device.serverTick()
    }

    private fun waitForTerminalText(
        device: K16RuntimeDevice,
        expected: String,
        description: String,
    ) = waitForTerminal(device, description) { terminal -> terminal.contains(expected) }

    private fun waitForTerminal(
        device: K16RuntimeDevice,
        description: String,
        attempts: Int = 8_000,
        predicate: (String) -> Boolean,
    ): String {
        repeat(attempts) {
            repeat(4) { device.serverTick() }
            val snapshot = device.snapshotRuntimeState()
            if (snapshot != null) {
                val control = snapshotControlState(snapshot)
                check(control.panicCode == 0) {
                    "K16 halted while waiting for $description: " +
                        "status=${control.status}, panicCode=${control.panicCode}, " +
                        "debug=${device.serialOutputSnapshot().decodeToString()}"
                }
                val terminal = terminalText(snapshot)
                if (predicate(terminal)) return terminal
            }
            val failure = device.runtimeFailureMessage
            check(failure == null) { "K16 runtime failed while waiting for $description: $failure" }
            Thread.sleep(5)
        }
        val terminal = device.snapshotRuntimeState()?.let(::terminalText) ?: "<no snapshot>"
        val debugOutput = device.serialOutputSnapshot().decodeToString()
        error(
            "K16 native TinyCC test did not observe $description; " +
                "isOn=${device.isOn}, failure=${device.runtimeFailureMessage}, debug=$debugOutput, terminal=$terminal",
        )
    }

    private fun commandOutput(
        terminal: String,
        command: String,
    ): String? {
        val marker = "K16> $command"
        val commandIndex = terminal.lastIndexOf(marker)
        if (commandIndex < 0) return null
        val outputStart = commandIndex + marker.length
        val promptIndex = terminal.indexOf("K16> ", startIndex = outputStart)
        if (promptIndex < 0) return null
        return terminal.substring(outputStart, promptIndex)
    }

    private fun String.normalizedWords(): String = trim().split(Regex("\\s+")).joinToString(" ")

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

    private fun snapshotCpuText(snapshot: ByteArray): String {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        val ramSize = buffer.getLong(0x10).toInt()
        val cpuOffset = 40 + ramSize
        val pc = buffer.getInt(cpuOffset + 16)
        val trapCause = buffer.getInt(cpuOffset + 24)
        val trapPc = buffer.getInt(cpuOffset + 28)
        val trapValue = buffer.getInt(cpuOffset + 32)
        val trapStackPointer = buffer.getInt(cpuOffset + 52)
        val registersOffset = cpuOffset + 56
        val sp = buffer.getInt(registersOffset + 14 * Int.SIZE_BYTES)
        return "pc=${pc.hex32()}, trapCause=${trapCause.hex32()}, trapPc=${trapPc.hex32()}, " +
            "trapValue=${trapValue.hex32()}, sp=${sp.hex32()}, trapSp=${trapStackPointer.hex32()}"
    }

    private fun Int.hex32(): String = "0x" + toUInt().toString(16).padStart(8, '0')

    private fun snapshotControlState(snapshot: ByteArray): SnapshotControlState {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        require(snapshot.copyOfRange(0, 8).contentEquals("K16SNAP\u0000".encodeToByteArray()))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10).toInt()
        val cpuCount = buffer.getInt(0x18)
        val deviceCount = buffer.getInt(0x20)
        var offset = headerSize + ramSize + cpuCount * K16_CPU_SNAPSHOT_RECORD_SIZE
        repeat(deviceCount) {
            val kind = buffer.getInt(offset)
            val payloadSize = buffer.getInt(offset + Int.SIZE_BYTES)
            if (kind == K16_CONTROL_SNAPSHOT_DEVICE_KIND) {
                require(payloadSize >= 2 * Int.SIZE_BYTES)
                return SnapshotControlState(
                    status = buffer.getInt(offset + 2 * Int.SIZE_BYTES),
                    panicCode = buffer.getInt(offset + 3 * Int.SIZE_BYTES),
                )
            }
            offset += 2 * Int.SIZE_BYTES + payloadSize
        }
        error("K16 snapshot has no control device")
    }

    private fun requiredPathProperty(name: String): Path {
        val path = System.getProperty(name)?.let(Path::of) ?: error("$name must be configured")
        assertTrue(Files.isRegularFile(path), "$name must point to a regular file: $path")
        return path
    }

    private fun requiredExecutableProperty(name: String): Path {
        val path = requiredPathProperty(name)
        assertTrue(Files.isExecutable(path), "$name must point to an executable: $path")
        return path
    }

    private fun runK16Tool(
        executable: Path,
        vararg args: String,
    ): String {
        val result = runProcess(listOf(executable.toString()) + args)
        assertTrue(result.exitCode == 0, "k16 ${args.joinToString(" ")} failed:\n${result.output}")
        return result.output
    }

    private fun runProcess(command: List<String>): ProcessResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        return ProcessResult(process.waitFor(), output)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    private data class SnapshotControlState(
        val status: Int,
        val panicCode: Int,
    )
}

private const val EXPECTED_OUTPUT = "native tinycc ok\n"
private const val K16_TERMINAL_CELLS_ADDR = 0x3000
private const val K16_TERMINAL_COLUMNS = 64
private const val K16_TERMINAL_ROWS = 25
private const val K16_CPU_SNAPSHOT_RECORD_SIZE = 208
private const val K16_CONTROL_SNAPSHOT_DEVICE_KIND = 1
