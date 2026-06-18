package ru.lazyhat.compukterkraft.impl

import org.junit.jupiter.api.Disabled
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.K16RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayFrameCodec
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntime
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16_VOLUME_MAGIC_BYTES
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val K16_KERNEL_LOAD_ADDR = 0x0000_4000
private const val K16_TERMINAL_CELLS_ADDR = 0x0000_3000
private const val K16_TERMINAL_COLUMNS = 53
private const val K16_TERMINAL_ROWS = 25
private const val LEGACY_KERNEL_SHELL_DISABLED =
    "Legacy kernel shell runtime path is no longer active; shell behavior is owned by a userland shell program."

class K16FirmwareResourceTest {
    @Test
    fun bundledK16FirmwareBuildUsesK16GradleSurface() {
        val source = Path.of("build.gradle.kts").readText()
        val rootBuildScript = Path.of("../../../build.gradle.kts").readText()
        val k16ToolchainSupport =
            Path.of("../../../build-scripts/src/main/kotlin/K16ToolchainSupport.kt").readText()

        assertTrue(source.contains("generated/k16-firmware-resources"))
        assertTrue(source.contains("generated/k16-firmware-artifacts"))
        assertTrue(source.contains("tasks.register(\"linkK16BiosFlash\")"))
        assertTrue(source.contains("fun Project.compileK16GuestRustBin("))
        assertTrue(source.contains("ProcessBuilder(command)"))
        assertTrue(source.contains("k16-cpu-helpers"))
        assertTrue(source.contains("rust/host/k16-tools/Cargo.toml"))
        assertTrue(source.contains("rootProject.tasks.named(\"prepareK16Toolchain\")"))
        assertTrue(source.contains("resolveK16Toolchain()"))
        assertTrue(rootBuildScript.contains("prepareK16Toolchain"))
        assertTrue(rootBuildScript.contains("stageK16Toolchain"))
        assertTrue(rootBuildScript.contains("downloadK16ToolchainArchive"))
        assertTrue(k16ToolchainSupport.contains("config/k16-toolchain.json"))
        assertTrue(k16ToolchainSupport.contains(".toolchain/k16"))
        assertTrue(k16ToolchainSupport.contains("must not be a symlink"))
        assertFalse(source.contains("providers.environmentVariable(\"K16_CARGO\")"))
        assertFalse(source.contains("providers.environmentVariable(\"K16_RUSTC\")"))
        assertFalse(source.contains("providers.environmentVariable(\"K16_LD\")"))
        assertTrue(source.contains("-C linker="))
        assertTrue(source.contains("-C link-arg=--k16-target=\$k16Target"))
        assertTrue(source.contains("\"opt-level=z\""))
        assertTrue(source.contains("-Copt-level=z"))
        assertTrue(source.contains("k16Target = \"bios\""))
        assertTrue(source.contains("k16Target = \"boot\""))
        assertTrue(source.contains("k16Target = \"kernel\""))
        assertTrue(source.contains("k16Target = \"program-dynamic\""))
        assertFalse(source.contains("k16Target = \"program\""))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16BiosObject\")"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16SystemBootObject\")"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16SystemKernelObject\")"))
        assertFalse(source.contains("--emit=obj"))
        assertTrue(source.contains("tasks.register<Exec>(\"compileK16SystemStorage0\")"))
        assertTrue(source.contains("rust/guest/k16-boot-chain"))
        assertTrue(source.contains("rust/guest/k16-bios"))
        assertTrue(source.contains("rust/guest/k16-boot"))
        assertTrue(source.contains("rust/guest/k16-kernel"))
        assertTrue(source.contains("rust/guest/k16-init"))
        assertTrue(source.contains("rust/guest/k16-shell"))
        assertTrue(source.contains("rust/guest/k16-ls"))
        assertTrue(source.contains("rust/guest/k16-cat"))
        assertTrue(source.contains("rust/guest/k16-alloc-test"))
        assertTrue(source.contains("k16InitManifest"))
        assertTrue(source.contains("k16InitSource"))
        assertTrue(source.contains("k16ShellManifest"))
        assertTrue(source.contains("k16ShellSource"))
        assertTrue(source.contains("k16LsManifest"))
        assertTrue(source.contains("k16LsSource"))
        assertTrue(source.contains("k16CatManifest"))
        assertTrue(source.contains("k16CatSource"))
        assertTrue(source.contains("k16AllocTestManifest"))
        assertTrue(source.contains("k16AllocTestSource"))
        assertTrue(source.contains("generatedK16ShellTarget"))
        assertTrue(source.contains("generatedK16LsTarget"))
        assertTrue(source.contains("generatedK16CatTarget"))
        assertTrue(source.contains("generatedK16AllocTestTarget"))
        assertTrue(source.contains("k16InitArtifact"))
        assertTrue(source.contains("k16ShellArtifact"))
        assertTrue(source.contains("k16LsArtifact"))
        assertTrue(source.contains("k16CatArtifact"))
        assertTrue(source.contains("k16AllocTestArtifact"))
        assertTrue(source.contains("compileK16SystemInit"))
        assertTrue(source.contains("compileK16SystemShell"))
        assertTrue(source.contains("compileK16SystemLs"))
        assertTrue(source.contains("compileK16SystemCat"))
        assertTrue(source.contains("compileK16SystemAllocTest"))
        assertTrue(source.contains("putK16SystemStorage0Init"))
        assertTrue(source.contains("binName = \"k16-init\""))
        assertTrue(
            source.contains("binName = \"k16-init\",\n                k16Target = \"program-dynamic\""),
            "init must use dynamic program startup so the kernel-provided translated stack pointer is preserved",
        )
        assertTrue(source.contains("binName = \"k16-shell\""))
        assertTrue(source.contains("binName = \"k16-ls\""))
        assertTrue(source.contains("binName = \"k16-cat\""))
        assertTrue(source.contains("binName = \"k16-alloc-test\""))
        assertTrue(
            source.contains("output = k16ShellArtifact.get().asFile,\n                buildStd = \"core,alloc\""),
            "shell uses alloc-backed input and should build core+alloc",
        )
        assertTrue(source.contains("\"/bin\""))
        assertTrue(source.contains("\"/etc\""))
        assertTrue(source.contains("\"/bin/init.kx\""))
        assertTrue(source.contains("\"/bin/shell.kx\""))
        assertTrue(source.contains("\"/bin/ls.kx\""))
        assertTrue(source.contains("\"/bin/cat.kx\""))
        assertTrue(source.contains("\"/bin/alloc-test.kx\""))
        assertTrue(source.contains("\"/etc/motd\""))
        assertTrue(source.contains("\"extract-partition\""))
        assertTrue(source.contains("\"replace-partition\""))
        assertTrue(source.contains("dir(\"rust/guest/k16-kernel/src\")"))
        assertTrue(source.contains("dir(\"rust/guest/k16-shell/src\")"))
        assertTrue(source.contains("inputs.dir(k16KernelSource)"))
        assertTrue(source.contains("inputs.dir(k16ShellSource)"))
        assertTrue(source.contains("toolchain.cli.absolutePath"))
        assertFalse(source.contains(".toolchain/build/cargo/k16-tools"))
        assertFalse(source.contains("environment(\"CARGO_TARGET_DIR\""))
        assertFalse(source.contains("generated/rux-firmware-"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileRux"))
        assertFalse(source.contains("--bin\", \"rux\""))

        val toolchainConfig = Path.of("../../../config/k16-toolchain.json").readText()
        assertTrue(toolchainConfig.contains("\"pin\""))
        assertTrue(toolchainConfig.contains("\"linux-x86_64\""))
        assertTrue(toolchainConfig.contains("\"bin/k16-ld\""))
        assertTrue(toolchainConfig.contains("\"bin/k16\""))
    }

    @Test
    fun bundledK16BiosFlashResourceExists() {
        val bytes =
            K16BiosFlashWorkspace.loadBiosFlashResource(
                classLoader = javaClass.classLoader,
            )

        assertTrue(bytes.size > 8, "K16 BIOS flash resource should not be empty")
        assertEquals(0, bytes.size % 2, "K16 BIOS flash resource should contain whole words")
    }

    @Test
    fun bundledK16BiosFlashResourceIsRawFlash() {
        val resource =
            javaClass.classLoader.getResourceAsStream("firmware/k16-bios.kflash")
                ?: error("raw K16 BIOS flash resource should exist")

        val bytes = resource.use { it.readBytes() }
        assertContentEquals(
            bytes,
            K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader),
        )
    }

    @Test
    fun bundledK16SystemStorage0VolumeResourceExists() {
        val bytes =
            K16SystemVolumeWorkspace.loadStorage0VolumeResource(
                classLoader = javaClass.classLoader,
            )

        assertTrue(bytes.size > 512, "K16 system storage0 volume resource should not be empty")
        assertContentEquals(K16_VOLUME_MAGIC_BYTES, bytes.copyOfRange(0, K16_VOLUME_MAGIC_BYTES.size))
    }

    @Test
    fun bundledK16SystemStorage0ContainsInitProgram() {
        val workspace = createTempDirectory("k16-init-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val init = workspace.resolve("init.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/init.kx",
            init.toString(),
        )

        val bytes = init.readBytes()
        assertTrue(bytes.size > 52, "bundled /bin/init.kx should be a non-empty K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(3, abiKind, "bundled /bin/init.kx must use K16E abi kind program")
    }

    @Test
    fun bundledK16SystemStorage0ContainsUnameProgram() {
        val workspace = createTempDirectory("k16-uname-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val uname = workspace.resolve("uname.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/uname.kx",
            uname.toString(),
        )

        val bytes = uname.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/uname.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(2, version, "bundled /bin/uname.kx must use dynamic K16E v2")
        assertEquals(3, abiKind, "bundled /bin/uname.kx must use K16E abi kind program")
    }

    @Test
    fun bundledK16SystemStorage0ContainsCatProgramAndMotdFile() {
        val workspace = createTempDirectory("k16-cat-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val cat = workspace.resolve("cat.kx")
        val motd = workspace.resolve("motd.txt")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/cat.kx",
            cat.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/etc/motd",
            motd.toString(),
        )

        val bytes = cat.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/cat.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(2, version, "bundled /bin/cat.kx must use dynamic K16E v2")
        assertEquals(3, abiKind, "bundled /bin/cat.kx must use K16E abi kind program")
        assertEquals("K16 FS OK\n", motd.readText())
    }

    @Test
    fun bundledK16SystemStorage0ContainsLsProgram() {
        val workspace = createTempDirectory("k16-ls-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val ls = workspace.resolve("ls.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/ls.kx",
            ls.toString(),
        )

        val bytes = ls.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/ls.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(2, version, "bundled /bin/ls.kx must use dynamic K16E v2")
        assertEquals(3, abiKind, "bundled /bin/ls.kx must use K16E abi kind program")
    }

    @Test
    fun bundledK16SystemStorage0ContainsAllocTestProgram() {
        val workspace = createTempDirectory("k16-alloc-test-storage-test-")
        val storage0 = workspace.resolve("storage0.kv")
        val root = workspace.resolve("root.kfs")
        val allocTest = workspace.resolve("alloc-test.kx")
        storage0.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        runK16Tool(
            "volume",
            "extract-partition",
            storage0.toString(),
            "ROOT",
            root.toString(),
        )
        runK16Tool(
            "fs",
            "kfs",
            "get",
            root.toString(),
            "/bin/alloc-test.kx",
            allocTest.toString(),
        )

        val bytes = allocTest.readBytes()
        assertTrue(bytes.size > 72, "bundled /bin/alloc-test.kx should be a non-empty dynamic K16E program")
        assertContentEquals(
            byteArrayOf('K'.code.toByte(), '1'.code.toByte(), '6'.code.toByte(), 'E'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        val version = ByteBuffer.wrap(bytes, 0x04, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val abiKind = ByteBuffer.wrap(bytes, 0x18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(2, version, "bundled /bin/alloc-test.kx must use dynamic K16E v2")
        assertEquals(3, abiKind, "bundled /bin/alloc-test.kx must use K16E abi kind program")
    }

    @Test
    fun k16RuntimeDeviceServerTicksAdvanceNativeTimer0Snapshot() {
        val workspace = createTempDirectory("k16-runtime-device-timer-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val device =
            K16RuntimeDevice(
                deviceId = 218,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                    )
                },
                stateSink = {},
            )

        try {
            device.turnOn()
            repeat(5) {
                device.serverTick()
            }

            val snapshot =
                requireNotNull(device.snapshotRuntimeState()) {
                    "K16 runtime device should expose a native machine snapshot while powered on"
                }

            assertEquals(5L, snapshotTimer0GameTicks(snapshot))
        } finally {
            device.close()
        }
    }

    @Test
    fun bundledK16SystemStorage0BootsRustKernel() {
        val workspace = createTempDirectory("k16-firmware-resource-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                val debug = runtime.outputSnapshot().decodeToString()

                assertEquals(
                    NativeK16ComputerControl.STATUS_READY,
                    control.status,
                    "panic code: ${control.panicCode}, debug: $debug",
                )
                assertTrue(debug.contains("K16 BOOT\n"), "bootloader debug output should remain visible; debug: $debug")
                assertFalse(debug.contains("K16 SHELL\n"), "shell output should go through stdout, not debug; debug: $debug")
                assertKernelGpuConsoleVisible(runtime, control, debug)
            }
    }

    @Test
    fun bundledK16ShellUsesFdStdinAndStdoutInsteadOfDebugOutput() {
        val source = Path.of("../../../rust/guest/k16-shell/src/main.rs").readText()

        assertTrue(source.contains("io::stdout()"))
        assertTrue(source.contains("io::stdin()"))
        assertTrue(source.contains("let mut input = InputLine::new()"))
        assertTrue(source.contains("let mut cwd = WorkingDirectory::new()"))
        assertTrue(source.contains("stdin.read(read_buffer)"))
        assertTrue(source.contains("dispatch_command("))
        assertTrue(source.contains("arg_paths"))
        assertTrue(source.contains("const PROMPT: &[u8] = b\"K16> \""))
        assertTrue(source.contains("const HELP: &[u8]"), "shell should define help text")
        assertTrue(source.contains("PWD\\nCD [PATH]"), "help should list cwd builtins")
        assertTrue(source.contains("LS [PATH...]\\nCAT <PATH...>"), "help should list filesystem utilities")
        assertTrue(source.contains("run_pwd(stdout, cwd)"))
        assertTrue(source.contains("run_cd(stdout, cwd, path_buffer, path)"))
        assertTrue(source.contains("run_ticks(stdout)"))
        assertTrue(source.contains("run_exec(stdout, cwd, arg_paths, program_path, name, args)"))
        val shellLibSource = Path.of("../../../rust/guest/k16-shell/src/lib.rs").readText()
        assertTrue(shellLibSource.contains("const BIN_PREFIX: &[u8] = b\"/bin/\""))
        assertTrue(shellLibSource.contains("const PROGRAM_SUFFIX: &[u8] = b\".kx\""))
        assertFalse(source.contains("process::exit(0)"))
        assertFalse(source.contains("debug::write_byte"))
    }

    @Test
    fun bundledK16ShellReadsKeyboardInputThroughFdStdin() {
        val workspace = createTempDirectory("k16-init-stdin-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val readyControl = runUntilTerminalText(runtime, "K16> ")
                assertEquals(
                    NativeK16ComputerControl.STATUS_READY,
                    readyControl.status,
                    "shell should wait for stdin after prompt; control: $readyControl; terminal: ${terminalText(runtime.machineSnapshot())}",
                )

                for (byte in "echo abc\n".encodeToByteArray()) {
                    runtime.pushKeyboardChar(byte)
                }
                val afterInputControl = continueUntilTerminalText(runtime, "abc", readyControl)
                val terminal = terminalText(runtime.machineSnapshot())

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertTrue(
                    terminal.contains("K16> echo abc") && terminal.contains("abc"),
                    "shell should read stdin bytes and dispatch echo through stdout; terminal: $terminal",
                )
            }
    }

    @Test
    fun bundledK16KernelLaunchesInitProgram() {
        val workspace = createTempDirectory("k16-init-launch-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                val debug = runtime.outputSnapshot().decodeToString()

                assertEquals(
                    NativeK16ComputerControl.STATUS_READY,
                    control.status,
                    "init should remain ready while waiting for fd stdin; panic code: ${control.panicCode}, debug: $debug",
                )
                assertTrue(debug.contains("K16 BOOT\n"), "bootloader debug output should remain visible; debug: $debug")
                assertFalse(debug.contains("K16 SHELL\n"), "shell output should go through stdout, not debug; debug: $debug")
                assertKernelGpuConsoleVisible(runtime, control, debug)
            }
    }

    @Test
    fun bundledK16SystemStorage0ReachesGpuConsoleWithDefaultRuntimeTicks() {
        val workspace = createTempDirectory("k16-firmware-default-tick-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                var control = runRuntimeServerTick(runtime)
                var frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                var sawVisibleFrame = frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixels() }

                var tick = 1
                while (tick < 24 && control.status != NativeK16ComputerControl.STATUS_READY) {
                    control = runRuntimeServerTick(runtime)
                    frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                    sawVisibleFrame = sawVisibleFrame || frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixels() }
                    tick += 1
                }

                val debug = runtime.outputSnapshot().decodeToString()
                assertEquals(
                    NativeK16ComputerControl.STATUS_READY,
                    control.status,
                    "default runtime ticks should boot through /bin/init.kx; tick: $tick, panic code: ${control.panicCode}, debug: $debug",
                )
                assertTrue(debug.contains("K16 BOOT\n"), "bootloader debug output should remain visible; debug: $debug")
                assertFalse(debug.contains("K16 SHELL\n"), "shell output should go through stdout, not debug; debug: $debug")
                assertTrue(
                    sawVisibleFrame,
                    "default runtime ticks should produce gpu0 console frames; tick: $tick, panic code: ${control.panicCode}, debug: $debug",
                )
            }
    }

    @Test
    fun k16KernelConsoleDoesNotUseDisplay0() {
        val kernelSourceDir = Path.of("../../../rust/guest/k16-kernel/src")
        val checkedFiles =
            listOf(
                "main.rs",
                "console.rs",
                "gpu.rs",
                "font.rs",
                "memory_layout.rs",
                "terminal.rs",
                "terminal_render.rs",
            )

        for (fileName in checkedFiles) {
            val source = kernelSourceDir.resolve(fileName).readText()
            assertFalse(
                source.contains("display0"),
                "$fileName must keep the kernel console path gpu0-only",
            )
        }
    }

    @Test
    fun k16BiosDoesNotUseDisplay0() {
        val biosSource = Path.of("../../../rust/guest/k16-bios/src/main.rs").readText()

        assertFalse(biosSource.contains("display0"), "K16 BIOS must render through gpu0 only")
        assertTrue(biosSource.contains("gpu0"), "K16 BIOS should use the gpu0 display path")
    }

    @Test
    fun k16KernelConsoleKeepsCellGridAndScrollsOnOverflow() {
        val kernelSourceDir = Path.of("../../../rust/guest/k16-kernel/src")
        val consoleSource = kernelSourceDir.resolve("console.rs").readText()
        val memoryLayoutSource = kernelSourceDir.resolve("memory_layout.rs").readText()
        val terminalSource = kernelSourceDir.resolve("terminal.rs").readText()
        val terminalRenderSource = kernelSourceDir.resolve("terminal_render.rs").readText()

        assertTrue(consoleSource.contains("use crate::terminal;"), "console facade should delegate to terminal state")
        assertFalse(consoleSource.contains("static mut CURSOR_X:"), "console facade must not own cursor state")
        assertFalse(consoleSource.contains("static mut GLYPH_BUFFER:"), "console facade must not own glyph buffers")
        assertFalse(consoleSource.contains("fn render_glyph("), "console facade must not rasterize glyphs")
        assertFalse(consoleSource.contains("fn blit_glyph("), "console facade must not blit glyphs")
        assertFalse(consoleSource.contains("const CELLS_ADDR:"), "console facade must not own guest cell storage")

        assertTrue(terminalSource.contains("const CELLS_ADDR:"), "terminal should keep guest cell state")
        assertTrue(
            terminalSource.contains("memory_layout::TERMINAL_CELLS_ADDR"),
            "terminal should use the shared kernel memory layout for guest cell storage",
        )
        assertTrue(
            memoryLayoutSource.contains("pub const TERMINAL_CELLS_ADDR: u32 = 0x0000_3000;"),
            "terminal cells should live below the kernel image instead of overlapping program memory",
        )
        assertFalse(
            memoryLayoutSource.contains("pub const TERMINAL_CELLS_ADDR: u32 = 0x0000_8000;"),
            "terminal cells must not overlap the program load address",
        )
        assertTrue(terminalSource.contains("static mut CURSOR_X:"), "terminal should own cursor state")
        assertTrue(terminalSource.contains("fn read_cell("), "terminal should read cells from guest RAM")
        assertTrue(terminalSource.contains("fn write_cell("), "terminal should write cells into guest RAM")
        assertTrue(terminalSource.contains("fn scroll_up("), "terminal should keep a bottom-overflow boundary")
        assertTrue(
            terminalSource.contains("copy_scrolled_cells();"),
            "bottom overflow should preserve true scroll contents in guest cell state",
        )
        assertTrue(
            terminalSource.contains("repaint_all_cells();"),
            "bottom overflow should repaint the scrolled guest cell grid through gpu0",
        )
        assertFalse(
            terminalSource.contains("else {\n            CURSOR_Y = 0;\n        }"),
            "bottom overflow must not wrap to row zero",
        )

        assertTrue(terminalRenderSource.contains("static mut GLYPH_BUFFER:"), "terminal renderer should own glyph buffers")
        assertTrue(terminalRenderSource.contains("fn render_glyph("), "terminal renderer should rasterize glyphs")
        assertTrue(terminalRenderSource.contains("fn blit_glyph("), "terminal renderer should blit glyphs")
        assertTrue(
            terminalRenderSource.contains("gpu::blit_buffer("),
            "terminal renderer should keep visible output on gpu0",
        )
    }

    @Test
    fun k16KernelTerminalDefinesReadableByteSemantics() {
        val terminalSource = Path.of("../../../rust/guest/k16-kernel/src/terminal.rs").readText()

        assertTrue(
            terminalSource.contains("pub fn clear() {\n    clear_terminal();\n}"),
            "terminal public clear should delegate to the named clear operation",
        )
        assertTrue(
            terminalSource.contains("b'\\n' => move_to_next_line(),"),
            "newline byte should move to the next terminal line",
        )
        assertTrue(
            terminalSource.contains("b'\\r' => move_to_column_start(),"),
            "carriage return byte should move to column zero",
        )
        assertTrue(
            terminalSource.contains("b'\\x0c' => clear_terminal(),"),
            "form feed should let userland stdout request a terminal clear",
        )
        assertTrue(
            terminalSource.contains("b'\\x08' => erase_previous_cell(),"),
            "backspace byte should erase the previous visible cell",
        )
        assertTrue(
            terminalSource.contains("b'\\t' => write_tab_spaces(),"),
            "tab byte should expand to terminal spaces",
        )
        assertTrue(
            terminalSource.contains("0x20..=0x7e => put_printable_byte(byte),"),
            "printable ASCII bytes should flow through the printable byte handler",
        )
    }

    @Test
    fun k16KernelReadSyscallUsesKeyboardInputPath() {
        val kernelSourceDir = Path.of("../../../rust/guest/k16-kernel/src")
        val syscallSource = kernelSourceDir.resolve("syscall.rs").readText()
        val stdinSource = kernelSourceDir.resolve("stdin.rs").readText()
        val mainSource = kernelSourceDir.resolve("main.rs").readText()

        assertTrue(mainSource.contains("mod stdin;"), "kernel should register fd stdin input")
        assertTrue(syscallSource.contains("abi_syscall::READ"), "syscall dispatch should handle fd reads")
        assertTrue(syscallSource.contains("abi_syscall::STAT"), "syscall dispatch should handle path metadata")
        assertTrue(
            syscallSource.contains("fs::stat_root_path(path)"),
            "STAT(path, len, out) should delegate to the ROOT/K16FS metadata path",
        )
        assertTrue(
            syscallSource.contains("stdin::read(ptr, len)"),
            "READ(FD_STDIN, ptr, len) should delegate to the stdin input path",
        )
        assertTrue(stdinSource.contains("keyboard0::EVENT_CHAR"), "stdin should consume keyboard character events")
        assertTrue(stdinSource.contains("keyboard0::EVENT_PASTE_BYTE"), "stdin should consume paste byte events")
        assertTrue(stdinSource.contains("k16_rt::wait_once()"), "blocking stdin reads should wait for host input without nesting syscalls")
    }

    @Test
    fun k16KernelLegacyShellPathIsRemovedFromCurrentSource() {
        val kernelSourceDir = Path.of("../../../rust/guest/k16-kernel/src")
        val mainSource = kernelSourceDir.resolve("main.rs").readText()
        val initSource = Path.of("../../../rust/guest/k16-init/src/main.rs").readText()
        val shellSource = Path.of("../../../rust/guest/k16-shell/src/main.rs").readText()

        assertFalse(Files.exists(kernelSourceDir.resolve("shell.rs")), "kernel shell dispatcher should be removed")
        assertFalse(Files.exists(kernelSourceDir.resolve("line.rs")), "kernel line discipline should be removed")
        assertFalse(Files.exists(kernelSourceDir.resolve("keyboard.rs")), "kernel keyboard line path should be removed")
        assertFalse(mainSource.contains("mod shell;"), "main.rs should not register the legacy shell module")
        assertFalse(mainSource.contains("shell::init();"), "kernel startup should not initialize the legacy shell module")
        assertTrue(Files.exists(Path.of("../../../rust/guest/k16-init/Cargo.toml")), "init should be a real launcher crate")
        assertTrue(initSource.contains("process::run(\"/bin/shell.kx\")"), "init should hand off to the userland shell")
        assertFalse(initSource.contains("fn dispatch_command("), "interactive shell dispatch should not live in init")
        assertTrue(shellSource.contains("fn dispatch_command("), "userland shell should own command dispatch")
    }

    @Test
    fun k16UserlandShellDefinesPromptAndBuiltins() {
        val shellSource = Path.of("../../../rust/guest/k16-shell/src/main.rs").readText()
        val shellLibSource = Path.of("../../../rust/guest/k16-shell/src/lib.rs").readText()

        assertTrue(shellSource.contains("const PROMPT: &[u8] = b\"K16> \""))
        assertTrue(shellSource.contains("fn dispatch_command("), "shell should name the dispatch boundary")
        assertTrue(shellLibSource.contains("use alloc::vec::Vec;"), "shell input should be backed by heap allocation")
        assertTrue(shellLibSource.contains("bytes: Vec<u8>"), "shell input should not use a fixed byte array")
        assertTrue(shellLibSource.contains("try_reserve(1)"), "input allocation failure should be deterministic")
        assertFalse(shellLibSource.contains("INPUT_CAPACITY"), "shell input should not carry the old fixed line cap")
        assertTrue(shellLibSource.contains("fn matches_command("), "shell should share command matching")
        assertTrue(shellLibSource.contains("fn is_echo_command("), "shell should name echo command matching")
        assertTrue(shellSource.contains("Command::Echo(bytes)"), "shell should handle the echo command")
        assertTrue(shellSource.contains("fn run_pwd("), "shell should name the pwd command")
        assertTrue(shellSource.contains("fn run_cd("), "shell should name the cd command")
        assertTrue(shellSource.contains("fn run_ticks("), "shell should name the ticks command")
        assertTrue(shellSource.contains("fn run_exec("), "shell should use generic exec dispatch")
        assertFalse(shellSource.contains("fn run_uname("), "uname should not need a hardcoded dispatch branch")
        assertFalse(shellSource.contains("fn run_ls("), "ls should not need a hardcoded dispatch branch")
        assertFalse(shellSource.contains("fn run_cat("), "cat should not need a hardcoded dispatch branch")
        assertFalse(shellSource.contains("fn run_alloc_test("), "alloc should not need a hardcoded dispatch branch")
        assertTrue(shellLibSource.contains("Command::Pwd"), "shell should classify the pwd command")
        assertTrue(shellLibSource.contains("Command::Cd("), "shell should classify cd with an optional path argument")
        assertTrue(shellLibSource.contains("Command::Exec"), "shell should classify non-builtins as generic exec")
        assertFalse(shellLibSource.contains("Command::Uname"), "shell should not carry a uname command variant")
        assertFalse(shellLibSource.contains("Command::Ls("), "shell should not carry an ls command variant")
        assertFalse(shellLibSource.contains("Command::Cat(&"), "shell should not carry a cat command variant")
        assertFalse(shellLibSource.contains("Command::AllocTest"), "shell should not carry an alloc command variant")
        assertTrue(
            shellSource.contains("HELP\\nCLEAR\\nPWD\\nCD [PATH]\\nECHO\\nTICKS\\nUNAME\\nLS [PATH...]\\nCAT <PATH...>\\nALLOC\\n"),
            "help should print a readable command list",
        )
        assertTrue(shellSource.contains("fs::metadata(path)"), "cd should validate paths through stat metadata")
        assertTrue(shellLibSource.contains("pub fn resolve_executable_path("), "executable path resolution should be tested in the shell library")
        assertTrue(shellSource.contains("resolve_executable_path(cwd, name, program_path)"), "shell should resolve executable paths through cwd")
        assertTrue(shellSource.contains("should_resolve_path_arg(name)"), "ls/cat should keep cwd-aware path args")
        assertTrue(shellLibSource.contains("const ALLOC_ALIAS: &[u8] = b\"alloc\""), "alloc should remain a shell alias")
        assertTrue(shellLibSource.contains("const ALLOC_PROGRAM: &[u8] = b\"alloc-test\""), "alloc should target alloc-test.kx")
        assertTrue(shellSource.contains("write_run_error(stdout, error)"), "missing programs should report run errors")
    }

    @Test
    fun k16CatUtilityReadsMotdThroughKraftStdFs() {
        val catSource = Path.of("../../../rust/guest/k16-cat/src/main.rs").readText()
        val catLibSource = Path.of("../../../rust/guest/k16-cat/src/lib.rs").readText()
        val stdSource = Path.of("../../../rust/guest/kraft-std/src/lib.rs").readText()

        assertTrue(catSource.contains("process::Argv::from_raw(argc, argv)"))
        assertTrue(catSource.contains("k16_cat::for_each_path_arg(argv.len(), |index| argv.get(index), print_file)"))
        assertTrue(catLibSource.contains("while index < arg_count"), "cat should visit every argv path")
        assertTrue(catSource.contains("fs::open(path)"))
        assertTrue(catSource.contains(".read("))
        assertTrue(catSource.contains(".close()"))
        assertTrue(catSource.contains("io::stdout()"))
        assertTrue(stdSource.contains("pub mod fs"), "kraft-std should expose a filesystem module")
        assertTrue(stdSource.contains("pub fn open(path: &str) -> Result<File, Error>"))
    }

    @Test
    fun k16LsUtilityListsEveryArgvPathThroughKraftStdFs() {
        val lsSource = Path.of("../../../rust/guest/k16-ls/src/main.rs").readText()
        val lsLibSource = Path.of("../../../rust/guest/k16-ls/src/lib.rs").readText()

        assertTrue(lsSource.contains("process::Argv::from_raw(argc, argv)"))
        assertTrue(lsSource.contains("k16_ls::for_each_path_arg_or_default(argv.len(), |index| argv.get(index), list_dir)"))
        assertTrue(lsSource.contains("static mut CHILD_PATH"), "ls should keep child path scratch space off the stack")
        assertTrue(lsLibSource.contains("pub const DEFAULT_PATH: &str = \"/bin\""))
        assertTrue(lsLibSource.contains("while index < arg_count"), "ls should visit every argv path")
        assertTrue(lsSource.contains("fs::read_dir(path, &mut buffer)"))
        assertTrue(lsSource.contains("fs::metadata(child_path)"))
        assertTrue(lsSource.contains("io::stdout()"))
    }

    @Test
    fun k16AllocTestUtilityUsesKraftStdAllocator() {
        val allocSource = Path.of("../../../rust/guest/k16-alloc-test/src/main.rs").readText()
        val stdSource = Path.of("../../../rust/guest/kraft-std/src/lib.rs").readText()

        assertTrue(allocSource.contains("extern crate alloc"))
        assertTrue(allocSource.contains("Vec::new()"))
        assertTrue(allocSource.contains(".push("))
        assertTrue(allocSource.contains("io::stdout()"))
        assertTrue(stdSource.contains("pub mod heap"), "kraft-std should expose heap helpers")
        assertTrue(stdSource.contains("#[global_allocator]"), "kraft-std should install the guest allocator")
        assertTrue(stdSource.contains("pub struct SbrkAllocator"), "kraft-std should name the sbrk allocator")
    }

    @Test
    fun k16UserlandTicksUsesKraftStdTimeApi() {
        val shellSource = Path.of("../../../rust/guest/k16-shell/src/main.rs").readText()
        val stdSource = Path.of("../../../rust/guest/kraft-std/src/lib.rs").readText()
        val timerSource = Path.of("../../../rust/guest/k16-kernel/src/timer.rs").readText()

        assertTrue(shellSource.contains("time::game_ticks_parts()"), "shell ticks should read time through kraft-std")
        assertTrue(shellSource.contains("b\"TICKS \""), "ticks should print a stable decimal prefix")
        assertTrue(shellSource.contains("fn write_decimal_parts("), "shell should format full-width timer parts")
        assertTrue(
            shellSource.contains("double_decimal_digits_and_add_bit"),
            "shell should format decimal without relying on 64-bit division",
        )
        assertTrue(stdSource.contains("pub mod time"), "kraft-std should expose a time module")
        assertTrue(stdSource.contains("pub fn game_ticks_parts() -> U64Parts"), "kraft-std should expose timer0 parts")
        assertTrue(timerSource.contains("pub type U64Parts = k16_rt::U64Parts"), "timer module should expose explicit low/high timer parts")
        assertTrue(timerSource.contains("pub struct TickInstant"), "timer module should expose a named tick instant")
        assertTrue(timerSource.contains("pub struct TickDuration"), "timer module should expose a named tick duration")
        assertTrue(timerSource.contains("pub fn now_ticks() -> TickInstant"), "timer module should expose current game ticks as an instant")
        assertTrue(
            timerSource.contains("pub fn monotonic_nanos() -> U64Parts"),
            "timer module should keep monotonic nanos as diagnostic parts",
        )
        assertFalse(timerSource.contains("k16_rt::trap_value()"), "timer value should come from MMIO instead of interrupt payload")
    }

    @Test
    fun k16UserlandShellDefinesReadableLineEditingSemantics() {
        val shellSource = Path.of("../../../rust/guest/k16-shell/src/main.rs").readText()

        assertTrue(
            shellSource.contains("0x20..=0x7e"),
            "printable input bytes should flow through a named printable branch",
        )
        assertTrue(
            shellSource.contains("b'\\x08' | 0x7f"),
            "backspace and delete should erase editable userland input",
        )
        assertTrue(
            shellSource.contains("b'\\n' | b'\\r'"),
            "newline and carriage return should complete the current userland line",
        )
        assertTrue(shellSource.contains("input.clear()"), "shell should clear stale line bytes before each prompt")
    }

    @Test
    fun k16KernelEntrypointLaunchesInitProgram() {
        val mainSource = Path.of("../../../rust/guest/k16-kernel/src/main.rs").readText()

        assertTrue(mainSource.contains("mod console;"), "kernel should register console for fd stdout")
        assertTrue(mainSource.contains("mod init;"), "kernel should register the init launcher module")
        assertTrue(mainSource.contains("console::init();"), "kernel should initialize console before userland")
        assertTrue(mainSource.contains("trap::initialize();"), "kernel should initialize traps before userland")
        assertTrue(mainSource.contains("control::set_ready();"), "kernel should mark the machine ready before entering init")
        assertTrue(mainSource.contains("init::launch()"), "kernel entrypoint should launch ROOT:/bin/init.kx")
        assertFalse(mainSource.contains("mod shell;"), "kernel entrypoint should not register the legacy shell")
        assertFalse(mainSource.contains("shell::init();"), "kernel entrypoint should not start the legacy shell")
        assertFalse(mainSource.contains("fn idle_once()"), "kernel entrypoint should not remain in the legacy idle loop")
    }

    @Test
    fun k16KernelFontCoversWorkingShellText() {
        val fontSource = Path.of("../../../rust/guest/k16-kernel/src/font.rs").readText()
        val shellSource = Path.of("../../../rust/guest/k16-shell/src/main.rs").readText()

        assertTrue(fontSource.contains("font_mono5x7::MONO5X7_ROWS"))
        assertTrue(fontSource.contains("font_mono5x7::FALLBACK_ROWS"))
        assertTrue(fontSource.contains("MONO5X7_ROWS[byte as usize]"))
        assertFalse(fontSource.contains("byte -"), "kernel font lookup should not use range-offset indexing")
        assertFalse(fontSource.contains("match byte"), "kernel font lookup should stay table-driven")
        assertFalse(shellSource.contains("fn display_byte("), "userland shell echo should not force uppercase display")
    }

    @Test
    fun k16KernelSleepTicksUsesTimer0MmioParts() {
        val timerSource = Path.of("../../../rust/guest/k16-kernel/src/timer.rs").readText()
        val runtimeSource = Path.of("../../../rust/guest/k16-rt/src/time.rs").readText()
        val runtimeLibSource = Path.of("../../../rust/guest/k16-rt/src/lib.rs").readText()

        assertTrue(
            timerSource.contains("k16_rt::timer0_game_ticks_parts()"),
            "kernel sleep_ticks should use the runtime full-width timer0 helper",
        )
        assertTrue(
            timerSource.contains("TickInstant::now()"),
            "kernel sleep_ticks should start from a named tick instant",
        )
        assertTrue(
            timerSource.contains("TickDuration::from_ticks(ticks)"),
            "kernel sleep_ticks should express the delay as a tick duration",
        )
        assertTrue(
            timerSource.contains("k16_rt::wait_once();"),
            "kernel sleep_ticks should use the resumable wait primitive while blocked",
        )
        assertFalse(
            timerSource.contains("k16_rt::yield_once();"),
            "kernel sleep_ticks should not busy-yield directly",
        )
        assertTrue(
            runtimeSource.contains("read_split_u64_parts(TIMER0_GAME_TICKS_LOW, TIMER0_GAME_TICKS_HIGH)"),
            "runtime should read full-width game ticks from timer0 MMIO",
        )
        assertTrue(
            runtimeSource.contains("read_split_u64_parts(TIMER0_MONOTONIC_NANOS_LOW, TIMER0_MONOTONIC_NANOS_HIGH)"),
            "runtime should read full-width monotonic nanos from timer0 MMIO",
        )
        assertTrue(
            runtimeLibSource.contains("timer0_game_ticks_parts") &&
                runtimeLibSource.contains("timer0_monotonic_nanos_parts") &&
                runtimeLibSource.contains("U64Parts"),
            "runtime should export the reusable low/high timer API",
        )
        assertTrue(timerSource.contains("fn checked_add("), "kernel sleep_ticks should build a full-width deadline")
        assertTrue(timerSource.contains("fn has_reached("), "kernel sleep_ticks should compare full-width deadlines")
        assertFalse(timerSource.contains("fn add_ticks("), "kernel sleep_ticks should not expose raw add helpers")
    }

    @Test
    fun k16KernelPayloadInspectToolDoesNotEnforceFixedWindowBudget() {
        val toolPath = Path.of("../../../tools/k16-kernel-payload-inspect.sh")

        assertTrue(Files.exists(toolPath), "K16 kernel payload inspect tool should exist")

        val source = toolPath.readText()
        assertTrue(source.contains("KERNEL_LOAD_ADDR=0x00004000"))
        assertTrue(source.contains("payload_bytes="))
        assertTrue(source.contains("memory_size="))
        assertFalse(source.contains("KERNEL_LIMIT_BYTES"))
        assertFalse(source.contains("MIN_HEADROOM_BYTES"))
        assertFalse(source.contains("headroom_bytes"))
    }

    @Test
    fun k16KernelTimerSmokeBuildUsesFirmwareSizeProfile() {
        val toolPath = Path.of("../../../tools/k16-kernel-timer-smoke.sh")

        assertTrue(Files.exists(toolPath), "K16 kernel timer smoke tool should exist")

        val source = toolPath.readText()
        assertTrue(source.contains("-Copt-level=z"))
        assertTrue(source.contains("-C opt-level=z"))
    }

    @Test
    fun bundledK16KernelPayloadUsesFixedLoadBaseWithoutFixedWindowBudget() {
        val artifactPath = Path.of("build/generated/k16-firmware-artifacts/display-ok.kx")
        val bytes = artifactPath.readBytes()

        assertContentEquals("K16E".encodeToByteArray(), bytes.copyOfRange(0, 4))
        assertEquals(1, bytes.u16Le(offset = 4), "K16E version")
        assertEquals(32, bytes.u16Le(offset = 6), "K16E header size")
        assertEquals(32, bytes.u32Le(offset = 16), "K16E section table offset")
        assertEquals(1, bytes.u32Le(offset = 20), "K16E section count")
        assertEquals(2, bytes.u32Le(offset = 24), "K16E ABI kind should be kernel")
        assertEquals(1, bytes.u32Le(offset = 32), "K16E section kind should be load")
        assertEquals(K16_KERNEL_LOAD_ADDR, bytes.u32Le(offset = 36), "K16E kernel load address")
        assertEquals(52, bytes.u32Le(offset = 40), "K16E kernel payload offset")

        val payloadBytes = bytes.u32Le(offset = 44)
        val memorySize = bytes.u32Le(offset = 48)

        assertTrue(payloadBytes > 0, "K16 kernel payload should not be empty")
        assertEquals(payloadBytes, memorySize, "K16E kernel memory size should match file size")
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelEchoesKeyboardCharThroughGpuConsole() {
        val workspace = createTempDirectory("k16-keyboard-console-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                runtime.pushKeyboardChar('O'.code.toByte())
                val afterInputControl = runRuntimeServerTick(runtime, maxTurns = 64)
                val frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertTrue(
                    frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixels() },
                    "keyboard char input should produce a new visible gpu0 console frame",
                )
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelShellHandoffHandlesEnterWithoutPanic() {
        val workspace = createTempDirectory("k16-shell-handoff-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                runtime.pushKeyboardChar('\n'.code.toByte())
                val afterInputControl = runRuntimeServerTick(runtime, maxTurns = 128)

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelShellRunsBasicCommandsWithoutPanic() {
        val workspace = createTempDirectory("k16-shell-commands-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                runShellCommand(runtime, "ok", expectVisiblePixels = true)
                runShellCommand(runtime, "help", expectVisiblePixels = true)
                runShellCommand(runtime, "clear", expectVisiblePixels = false)
                runShellCommand(runtime, "echo ok", expectVisiblePixels = true)
                runShellCommand(runtime, "ticks", expectVisiblePixels = true)
                runShellCommand(runtime, "wat", expectVisiblePixels = true)
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelTicksCommandReadsAdvancingTimer0() {
        val workspace = createTempDirectory("k16-shell-ticks-output-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                runShellCommand(runtime, "clear", expectVisiblePixels = false)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                val currentTicks = snapshotTimer0GameTicks(runtime.machineSnapshot())
                val deltaToMakeCommandPrintKnownValue = 14L
                val ticksAfterManualAdvance = currentTicks + deltaToMakeCommandPrintKnownValue
                val expectedTicksAfterCommand = ticksAfterManualAdvance + 1
                runtime.advanceGameTicks(deltaToMakeCommandPrintKnownValue)
                assertEquals(
                    ticksAfterManualAdvance,
                    snapshotTimer0GameTicks(runtime.machineSnapshot()),
                    "runtime.advanceGameTicks should advance timer0 in the machine snapshot before the shell command runs",
                )
                for (byte in "ticks\n".encodeToByteArray()) {
                    runtime.pushKeyboardChar(byte)
                }
                val afterTicksControl = runRuntimeServerTick(runtime, maxTurns = 256)
                assertEquals(
                    expectedTicksAfterCommand,
                    snapshotTimer0GameTicks(runtime.machineSnapshot()),
                    "server tick should add one timer0 game tick before executing the shell command",
                )
                val terminalRow =
                    snapshotRamBytes(runtime.machineSnapshot(), start = 0x1_3000 + 53, size = 53)
                        .toString(Charsets.US_ASCII)
                val expectedTerminalPrefix = "TICKS ${expectedTicksAfterCommand and 0xffff_ffffL}"

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterTicksControl.status)
                assertTrue(
                    terminalRow.startsWith(expectedTerminalPrefix),
                    "ticks output should read the current timer0 value; expected prefix: $expectedTerminalPrefix, " +
                        "actual terminal row: $terminalRow",
                )
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelTicksCommandReadsFullWidthTimer0FromMmio() {
        val workspace = createTempDirectory("k16-shell-ticks-u64-output-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        val bootedSnapshot =
            K16ComputerRuntimeFactory
                .createFromBiosFlash(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                ).use { runtime ->
                    val control = runThroughBiosSplashAndBoot(runtime)
                    assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                    runtime.machineSnapshot()
                }
        val restoredGameTicks = 0x0000_0001_0000_002aL
        val expectedTicksAfterCommand = restoredGameTicks + 2
        val highTimerSnapshot = snapshotWithTimer0GameTicks(bootedSnapshot, restoredGameTicks)

        K16ComputerRuntimeFactory
            .restoreFromBiosFlashSnapshot(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
                snapshot = highTimerSnapshot,
            ).use { runtime ->
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                runShellCommand(runtime, "clear", expectVisiblePixels = false)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                for (byte in "ticks\n".encodeToByteArray()) {
                    runtime.pushKeyboardChar(byte)
                }
                val afterTicksControl = runRuntimeServerTick(runtime, maxTurns = 256)
                val terminalRow =
                    snapshotRamBytes(runtime.machineSnapshot(), start = 0x1_3000 + 53, size = 53)
                        .toString(Charsets.US_ASCII)
                val expectedTerminalPrefix = "TICKS $expectedTicksAfterCommand"

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterTicksControl.status)
                assertTrue(
                    terminalRow.startsWith(expectedTerminalPrefix),
                    "ticks output should read full timer0 MMIO value; expected prefix: $expectedTerminalPrefix, " +
                        "actual terminal row: $terminalRow",
                )
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelShellDispatcherKeepsCurrentCommandsAlive() {
        val workspace = createTempDirectory("k16-shell-dispatcher-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                runShellCommand(runtime, "", expectVisiblePixels = true)
                runShellCommand(runtime, "ok", expectVisiblePixels = true)
                runShellCommand(runtime, "help", expectVisiblePixels = true)
                runShellCommand(runtime, "clear", expectVisiblePixels = false)
                runShellCommand(runtime, "echo ok", expectVisiblePixels = true)
                runShellCommand(runtime, "ticks", expectVisiblePixels = true)
                runShellCommand(runtime, "wat", expectVisiblePixels = true)
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelShellRendersPrintableAsciiInputThroughGpuConsole() {
        val workspace = createTempDirectory("k16-shell-printable-ascii-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                for (byte in "echo abc xyz 0123456789 !?\n".encodeToByteArray()) {
                    runtime.pushKeyboardChar(byte)
                }
                val afterInputControl = runRuntimeServerTick(runtime, maxTurns = 512)
                val frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                val framebuffer = composeRgb565Framebuffer(frames, width = 320, height = 200)

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertContentEquals(
                    intArrayOf(0, 0, 0, 0, 0, 0, 0),
                    framebuffer.glyphRowsAt(x = 9 * 6, y = 9),
                    "printable ASCII input should render space as a blank glyph",
                )
                assertContentEquals(
                    intArrayOf(0b00000, 0b00000, 0b01110, 0b00001, 0b01111, 0b10001, 0b01111),
                    framebuffer.glyphRowsAt(x = 10 * 6, y = 9),
                    "printable ASCII input should render lowercase a through the guest kernel font",
                )
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelLineDisciplineHandlesBackspaceAndEnter() {
        val workspace = createTempDirectory("k16-line-discipline-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                runtime.pushKeyboardChar('A'.code.toByte())
                runtime.pushKeyboardChar('B'.code.toByte())
                runtime.pushKeyboardChar('\b'.code.toByte())
                runtime.pushKeyboardChar('C'.code.toByte())
                runtime.pushKeyboardChar('\n'.code.toByte())
                val afterInputControl = runRuntimeServerTick(runtime, maxTurns = 128)
                val frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
                assertTrue(
                    frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixels() },
                    "line discipline editing should produce visible gpu0 console frames",
                )
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelLineDisciplineHandlesEmptyBackspaceCarriageReturnAndOverflow() {
        val workspace = createTempDirectory("k16-line-contract-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                runtime.pushKeyboardChar('\b'.code.toByte())
                runtime.pushKeyboardChar('A'.code.toByte())
                var afterInputControl = runRuntimeServerTick(runtime, maxTurns = 128)
                var frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                val framebuffer = composeRgb565Framebuffer(frames, width = 320, height = 200)

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
                assertContentEquals(
                    intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
                    framebuffer.glyphRowsAt(x = 5 * 6, y = 9),
                    "empty-line backspace should not move the cursor before the first input cell",
                )

                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                runtime.pushKeyboardChar('\r'.code.toByte())
                afterInputControl = runRuntimeServerTick(runtime, maxTurns = 128)
                frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
                assertTrue(
                    frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 },
                    "carriage return should complete the line and produce gpu0 terminal frames",
                )

                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                repeat(140) {
                    runtime.pushKeyboardChar('a'.code.toByte())
                }
                runtime.pushKeyboardChar('\n'.code.toByte())
                afterInputControl = runRuntimeServerTick(runtime, maxTurns = 1_024)
                frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
                assertTrue(
                    frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixels() },
                    "line overflow should reject extra input without panicking or losing gpu0 output",
                )
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelBackspaceAfterAutoWrapReturnsToPreviousRow() {
        val workspace = createTempDirectory("k16-line-wrap-backspace-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                repeat(49) {
                    runtime.pushKeyboardChar('a'.code.toByte())
                }
                runtime.pushKeyboardChar('\b'.code.toByte())
                runtime.pushKeyboardChar('\b'.code.toByte())
                runtime.pushKeyboardChar('z'.code.toByte())
                val afterInputControl = runRuntimeServerTick(runtime, maxTurns = 512)
                val secondTerminalRow = snapshotRamBytes(runtime.machineSnapshot(), start = 0x1_3000 + 53, size = 53)
                val thirdTerminalRow = snapshotRamBytes(runtime.machineSnapshot(), start = 0x1_3000 + 53 * 2, size = 53)

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
                assertEquals(
                    'z'.code.toByte(),
                    secondTerminalRow[52],
                    "backspace at the start of an auto-wrapped row should return to the previous row",
                )
                assertEquals(
                    ' '.code.toByte(),
                    thirdTerminalRow[0],
                    "the wrapped row should be blank after erasing the wrapped character and previous-row character",
                )
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelTerminalEditingClearAndScrollStayVisible() {
        val workspace = createTempDirectory("k16-terminal-contract-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                for (byte in byteArrayOf('A'.code.toByte(), 'B'.code.toByte(), '\b'.code.toByte(), 'C'.code.toByte(), '\n'.code.toByte())) {
                    runtime.pushKeyboardChar(byte)
                }
                var afterInputControl = runRuntimeServerTick(runtime, maxTurns = 128)
                var frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                var framebuffer = composeRgb565Framebuffer(frames, width = 320, height = 200)

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
                assertContentEquals(
                    intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
                    framebuffer.glyphRowsAt(x = 5 * 6, y = 9),
                    "editable terminal input should leave the first typed glyph after the prompt",
                )
                assertContentEquals(
                    intArrayOf(0b01111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b01111),
                    framebuffer.glyphRowsAt(x = 6 * 6, y = 9),
                    "backspace should erase B so C occupies the second typed cell",
                )
                assertContentEquals(
                    intArrayOf(0, 0, 0, 0, 0, 0, 0),
                    framebuffer.glyphRowsAt(x = 7 * 6, y = 9),
                    "backspace editing should not leave a stale third glyph",
                )

                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                for (byte in "clear\n".encodeToByteArray()) {
                    runtime.pushKeyboardChar(byte)
                }
                afterInputControl = runRuntimeServerTick(runtime, maxTurns = 256)
                frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                framebuffer = composeRgb565Framebuffer(frames, width = 320, height = 200)

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
                assertContentEquals(
                    intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
                    framebuffer.glyphRowsAt(x = 0, y = 1),
                    "clear should redraw the shell prompt at the first terminal row",
                )
                assertContentEquals(
                    intArrayOf(0, 0, 0, 0, 0, 0, 0),
                    framebuffer.glyphRowsAt(x = 0, y = 9),
                    "clear should leave the second terminal row blank",
                )

                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                repeat(30) {
                    runtime.pushKeyboardChar('\n'.code.toByte())
                }
                afterInputControl = runRuntimeServerTick(runtime, maxTurns = 2_048)
                frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
                assertTrue(
                    frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixelsAtOrBelow(globalY = 24 * 8) },
                    "blank-line overflow should keep rendering visible terminal pixels on the bottom row after scroll",
                )
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelConsumesKeyboardKeyEventsWithoutPanic() {
        val workspace = createTempDirectory("k16-keyboard-key-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)

                runtime.pushKeyboardKeyDown(key = 65, repeat = false)
                runtime.pushKeyboardKeyUp(key = 65)
                val afterInputControl = runRuntimeServerTick(runtime, maxTurns = 64)

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
                assertEquals(0, afterInputControl.panicCode)
            }
    }

    @Test
    @Disabled(LEGACY_KERNEL_SHELL_DISABLED)
    fun bundledK16KernelShellHandlesEnterAndBackspaceKeyEvents() {
        val workspace = createTempDirectory("k16-shell-special-keys-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                "cleax".forEach { runtime.pushKeyboardChar(it.code.toByte()) }
                assertEquals(NativeK16ComputerControl.STATUS_READY, runRuntimeServerTick(runtime, maxTurns = 256).status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                runtime.pushKeyboardKeyDown(key = KeyCodes.KEY_BACKSPACE, repeat = false)
                assertEquals(NativeK16ComputerControl.STATUS_READY, runRuntimeServerTick(runtime, maxTurns = 256).status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                runtime.pushKeyboardChar('r'.code.toByte())
                assertEquals(NativeK16ComputerControl.STATUS_READY, runRuntimeServerTick(runtime, maxTurns = 256).status)
                NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                runtime.pushKeyboardKeyDown(key = KeyCodes.KEY_ENTER, repeat = false)
                val afterEnterControl = runRuntimeServerTick(runtime, maxTurns = 256)
                val frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                val framebuffer = composeRgb565Framebuffer(frames, width = 320, height = 200)

                assertEquals(NativeK16ComputerControl.STATUS_READY, afterEnterControl.status)
                assertEquals(0, afterEnterControl.panicCode)
                assertTrue(
                    frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 },
                    "Enter key-down should complete the clear command and produce a gpu0 frame",
                )
                assertContentEquals(
                    intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
                    framebuffer.glyphRowsAt(x = 0, y = 1),
                    "Backspace key-down should erase x so cleax+r becomes clear and redraws the prompt",
                )
                assertContentEquals(
                    intArrayOf(0, 0, 0, 0, 0, 0, 0),
                    framebuffer.glyphRowsAt(x = 0, y = 9),
                    "clear via Enter key-down should leave the second terminal row blank",
                )
            }
    }

    @Test
    fun bundledK16BiosSplashIsObservableBeforeStorageBoot() {
        val workspace = createTempDirectory("k16-firmware-splash-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val splashControl = runRuntimeServerTick(runtime)
                val splashFrames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

                assertEquals(NativeK16ComputerControl.STATUS_BOOTING, splashControl.status)
                assertTrue(
                    splashFrames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixels() },
                    "BIOS splash should be visible through gpu0",
                )

                var bootControl = splashControl
                var tick = 1
                while (tick < 24 && bootControl.status != NativeK16ComputerControl.STATUS_READY) {
                    bootControl = runRuntimeServerTick(runtime, maxTurns = 1_000_000)
                    tick += 1
                }
                val debug = runtime.outputSnapshot().decodeToString()

                assertEquals(NativeK16ComputerControl.STATUS_READY, bootControl.status)
                assertTrue(debug.contains("K16 BOOT\n"), "bootloader debug output should remain visible; debug: $debug")
                assertFalse(debug.contains("K16 SHELL\n"), "shell output should go through stdout, not debug; debug: $debug")
                assertKernelGpuConsoleVisible(runtime, bootControl, debug)
            }
    }

    @Test
    fun bundledK16BiosSplashRendersDistinctBannerGlyphs() {
        val workspace = createTempDirectory("k16-firmware-splash-glyph-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory
            .createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                runRuntimeServerTick(runtime)
                val splashFrames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
                val framebuffer = composeRgb565Framebuffer(splashFrames, width = 320, height = 200)

                val expectedGlyphs =
                    mapOf(
                        0 to intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
                        1 to intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
                        2 to intArrayOf(0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110),
                        4 to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110),
                        5 to intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111),
                        6 to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
                        7 to intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110),
                    )
                for ((column, rows) in expectedGlyphs) {
                    assertContentEquals(
                        rows,
                        framebuffer.glyphRowsAt(x = 8 + column * 8, y = 8),
                        "BIOS banner glyph column $column should match K16 BIOS text instead of fallback glyphs",
                    )
                }
            }
    }

    @Test
    fun bundledK16SystemStorage0RestoresRustKernelRuntimeSnapshot() {
        val workspace = createTempDirectory("k16-firmware-restore-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        val machineSnapshot =
            K16ComputerRuntimeFactory
                .createFromBiosFlash(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                ).use { runtime ->
                    val control = runThroughBiosSplashAndBoot(runtime)
                    assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                    runtime.machineSnapshot()
                }
        val storage0BeforeRestore = storage0Path.readBytes()

        K16ComputerRuntimeFactory
            .restoreFromBiosFlashSnapshot(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
                snapshot = machineSnapshot,
            ).use { restored ->
                val control = restored.control()

                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                assertEquals(0, control.panicCode)
            }
        assertContentEquals(storage0BeforeRestore, storage0Path.readBytes())
    }

    private fun DisplayFrameDelta.hasVisiblePixels(): Boolean =
        tiles.any { tile ->
            tile.payload.asSequence().any { it != 0.toByte() }
        }

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun composeRgb565Framebuffer(
        frames: List<DisplayFrameDelta>,
        width: Int,
        height: Int,
    ): IntArray {
        val pixels = IntArray(width * height)
        for (frame in frames) {
            require(frame.width == width && frame.height == height)
            require(frame.pixelFormat == DisplayPixelFormat.RGB565)
            if (frame.fullRefresh) pixels.fill(0)
            for (tile in frame.tiles) {
                var offset = 0
                var row = 0
                while (row < tile.height) {
                    var column = 0
                    while (column < tile.width) {
                        val hi = tile.payload[offset++].toInt() and 0xFF
                        val lo = tile.payload[offset++].toInt() and 0xFF
                        pixels[(tile.y + row) * width + tile.x + column] = (hi shl 8) or lo
                        column += 1
                    }
                    row += 1
                }
            }
        }
        return pixels
    }

    private fun IntArray.glyphRowsAt(
        x: Int,
        y: Int,
    ): IntArray {
        val rows = IntArray(7)
        var row = 0
        while (row < rows.size) {
            var bits = 0
            var column = 0
            while (column < 5) {
                if (this[(y + row) * 320 + x + column] != 0) {
                    bits = bits or (1 shl (4 - column))
                }
                column += 1
            }
            rows[row] = bits
            row += 1
        }
        return rows
    }

    private fun assertKernelGpuConsoleVisible(
        runtime: K16ComputerRuntime,
        control: NativeK16ComputerControl,
        debug: String,
    ) {
        val frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
        assertTrue(frames.isEmpty() || frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 })
    }

    private fun DisplayFrameDelta.hasVisiblePixelsAtOrBelow(globalY: Int): Boolean =
        tiles.any { tile ->
            var row = 0
            while (row < tile.height) {
                if (tile.y + row >= globalY) {
                    var column = 0
                    while (column < tile.width) {
                        val offset = (row * tile.width + column) * 2
                        if (tile.payload[offset] != 0.toByte() || tile.payload[offset + 1] != 0.toByte()) {
                            return@any true
                        }
                        column += 1
                    }
                }
                row += 1
            }
            false
        }

    private fun runThroughBiosSplashAndBoot(runtime: K16ComputerRuntime): NativeK16ComputerControl {
        val splashControl = runRuntimeServerTick(runtime)
        assertEquals(NativeK16ComputerControl.STATUS_BOOTING, splashControl.status)
        var control = splashControl
        var tick = 1
        while (tick < 24 && control.status != NativeK16ComputerControl.STATUS_READY &&
            control.status != NativeK16ComputerControl.STATUS_HALTED
        ) {
            control = runRuntimeServerTick(runtime, maxTurns = 1)
            tick += 1
        }
        return control
    }

    private fun runUntilTerminalText(
        runtime: K16ComputerRuntime,
        expected: String,
    ): NativeK16ComputerControl {
        var control = runThroughBiosSplashAndBoot(runtime)
        return continueUntilTerminalText(runtime, expected, control)
    }

    private fun continueUntilTerminalText(
        runtime: K16ComputerRuntime,
        expected: String,
        initialControl: NativeK16ComputerControl,
    ): NativeK16ComputerControl {
        var control = initialControl
        repeat(32) {
            val terminal = terminalText(runtime.machineSnapshot())
            if (terminal.contains(expected)) {
                return control
            }
            control = runRuntimeServerTick(runtime, maxTurns = 1_000_000)
        }
        val snapshot = runtime.machineSnapshot()
        val terminal = terminalText(snapshot)
        val debug = runtime.outputSnapshot().decodeToString()
        error(
            "K16 firmware test did not observe '$expected'; status: ${control.status}; " +
                snapshotBootCpuDebug(snapshot) + "; " +
                "keyboard0Events: ${snapshotKeyboard0EventCount(snapshot)}; debug: $debug; terminal: $terminal",
        )
    }

    private fun runRuntimeServerTick(
        runtime: K16ComputerRuntime,
        maxTurns: Int = 8,
    ): NativeK16ComputerControl {
        runtime.advanceGameTicks(1)
        return runtime.tick(maxTurns = maxTurns)
    }

    private fun runShellCommand(
        runtime: K16ComputerRuntime,
        command: String,
        expectVisiblePixels: Boolean,
    ) {
        for (byte in "$command\n".encodeToByteArray()) {
            runtime.pushKeyboardChar(byte)
        }
        val control = runRuntimeServerTick(runtime, maxTurns = 256)
        val frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

        assertEquals(NativeK16ComputerControl.STATUS_READY, control.status, "command: $command")
        assertEquals(0, control.panicCode, "command: $command")
        assertTrue(
            frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 },
            "shell command should produce gpu0 frames; command: $command",
        )
        assertTrue(
            !expectVisiblePixels || frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixels() },
            "shell command should produce visible gpu0 frames; command: $command",
        )
    }

    private fun snapshotTimer0GameTicks(snapshot: ByteArray): Long {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        val cpuCount = buffer.getInt(0x18)
        val deviceCount = buffer.getInt(0x20)
        var offset = headerSize + ramSize.toInt() + cpuCount * K16_SNAPSHOT_CPU_RECORD_SIZE
        repeat(deviceCount) {
            val deviceKind = buffer.getInt(offset)
            val payloadSize = buffer.getInt(offset + 4)
            val payloadOffset = offset + K16_SNAPSHOT_DEVICE_HEADER_SIZE
            if (deviceKind == K16_SNAPSHOT_TIMER0_DEVICE_KIND) {
                assertEquals(K16_SNAPSHOT_TIMER0_PAYLOAD_SIZE, payloadSize)
                return buffer.getLong(payloadOffset)
            }
            offset = payloadOffset + payloadSize
        }
        error("K16SNAP did not contain a timer0 device record")
    }

    private fun snapshotBootCpuDebug(snapshot: ByteArray): String {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        val cpuCount = buffer.getInt(0x18)
        require(cpuCount > 0)
        val cpuOffset = headerSize + ramSize.toInt()
        return "bootPc: ${buffer.getInt(cpuOffset + 0x10).toString(16)}; " +
            "trapPc: ${buffer.getInt(cpuOffset + 0x1c).toString(16)}; " +
            "trapCause: ${buffer.getInt(cpuOffset + 0x18).toString(16)}; " +
            "trapValue: ${buffer.getInt(cpuOffset + 0x20).toString(16)}; " +
            "sp: ${buffer.getInt(cpuOffset + 0x38 + 15 * 4).toString(16)}; " +
            "r0: ${buffer.getInt(cpuOffset + 0x38).toString(16)}"
    }

    private fun snapshotKeyboard0EventCount(snapshot: ByteArray): Int {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        val cpuCount = buffer.getInt(0x18)
        val deviceCount = buffer.getInt(0x20)
        var offset = headerSize + ramSize.toInt() + cpuCount * K16_SNAPSHOT_CPU_RECORD_SIZE
        repeat(deviceCount) {
            val deviceKind = buffer.getInt(offset)
            val payloadSize = buffer.getInt(offset + 4)
            val payloadOffset = offset + K16_SNAPSHOT_DEVICE_HEADER_SIZE
            if (deviceKind == K16_SNAPSHOT_KEYBOARD0_DEVICE_KIND) {
                return buffer.getInt(payloadOffset + 12)
            }
            offset = payloadOffset + payloadSize
        }
        error("K16SNAP did not contain a keyboard0 device record")
    }

    private fun snapshotWithTimer0GameTicks(
        snapshot: ByteArray,
        gameTicks: Long,
    ): ByteArray {
        val editedSnapshot = snapshot.copyOf()
        val buffer = ByteBuffer.wrap(editedSnapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        val cpuCount = buffer.getInt(0x18)
        val deviceCount = buffer.getInt(0x20)
        var offset = headerSize + ramSize.toInt() + cpuCount * K16_SNAPSHOT_CPU_RECORD_SIZE
        repeat(deviceCount) {
            val deviceKind = buffer.getInt(offset)
            val payloadSize = buffer.getInt(offset + 4)
            val payloadOffset = offset + K16_SNAPSHOT_DEVICE_HEADER_SIZE
            if (deviceKind == K16_SNAPSHOT_TIMER0_DEVICE_KIND) {
                assertEquals(K16_SNAPSHOT_TIMER0_PAYLOAD_SIZE, payloadSize)
                buffer.putLong(payloadOffset, gameTicks)
                return editedSnapshot
            }
            offset = payloadOffset + payloadSize
        }
        error("K16SNAP did not contain a timer0 device record")
    }

    private fun snapshotRamBytes(
        snapshot: ByteArray,
        start: Int,
        size: Int,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("K16SNAP\u0000".encodeToByteArray(), snapshot.copyOfRange(0, 8))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        require(start >= 0 && size >= 0 && start + size <= ramSize)
        return snapshot.copyOfRange(headerSize + start, headerSize + start + size)
    }

    private fun terminalText(snapshot: ByteArray): String =
        snapshotRamBytes(
            snapshot = snapshot,
            start = K16_TERMINAL_CELLS_ADDR,
            size = K16_TERMINAL_ROWS * K16_TERMINAL_COLUMNS,
        ).map { byte -> if (byte in 0x20..0x7e) byte.toInt().toChar() else ' ' }
            .joinToString(separator = "")

    private fun runK16Tool(vararg args: String) {
        val executable = k16ToolExecutable()
        assertTrue(Files.isExecutable(executable), "K16 tool should be executable at $executable")
        val process =
            ProcessBuilder(listOf(executable.toString()) + args.toList())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, "k16 ${args.joinToString(" ")} failed:\n$output")
    }

    private fun k16ToolExecutable(): Path {
        val toolchainConfig = Path.of("../../../config/k16-toolchain.json").readText()
        val pin =
            Regex(""""pin"\s*:\s*"([^"]+)"""")
                .find(toolchainConfig)
                ?.groupValues
                ?.get(1)
                ?: error("K16 toolchain config should declare pin")
        val root = Path.of("../../../.toolchain/k16/$pin/${currentK16ToolchainHostId()}")
        assertTrue(Files.isDirectory(root), "K16 toolchain root should exist at $root")
        return root.resolve("bin/k16")
    }

    private fun currentK16ToolchainHostId(): String {
        val osName = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val os =
            when {
                osName.contains("linux") -> "linux"
                osName.contains("mac") || osName.contains("darwin") -> "macos"
                osName.contains("windows") -> "windows"
                else -> error("Unsupported K16 toolchain OS: $osName")
            }
        val cpu =
            when (arch) {
                "x86_64", "amd64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> error("Unsupported K16 toolchain architecture: $arch")
            }
        return "$os-$cpu"
    }
}

private const val K16_SNAPSHOT_CPU_RECORD_SIZE = 204
private const val K16_SNAPSHOT_DEVICE_HEADER_SIZE = 8
private const val K16_SNAPSHOT_TIMER0_DEVICE_KIND = 6
private const val K16_SNAPSHOT_KEYBOARD0_DEVICE_KIND = 7
private const val K16_SNAPSHOT_TIMER0_PAYLOAD_SIZE = 8
