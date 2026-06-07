package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.device.vm.display.NativeDisplayFrameCodec
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerDisplaySnapshot
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntime
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16_VOLUME_MAGIC_BYTES
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
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
        assertTrue(source.contains("k16Target = \"bios\""))
        assertTrue(source.contains("k16Target = \"boot\""))
        assertTrue(source.contains("k16Target = \"kernel\""))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16BiosObject\")"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16SystemBootObject\")"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16SystemKernelObject\")"))
        assertFalse(source.contains("--emit=obj"))
        assertTrue(source.contains("tasks.register<Exec>(\"compileK16SystemStorage0\")"))
        assertTrue(source.contains("rust/guest/k16-boot-chain"))
        assertTrue(source.contains("rust/guest/k16-bios"))
        assertTrue(source.contains("rust/guest/k16-boot"))
        assertTrue(source.contains("rust/guest/k16-kernel"))
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
        val resource = javaClass.classLoader.getResourceAsStream("firmware/k16-bios.kflash")
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
    fun bundledK16SystemStorage0BootsRustKernel() {
        val workspace = createTempDirectory("k16-firmware-resource-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory.createFromBiosFlash(
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
            assertKernelGpuConsoleVisible(runtime, control, debug)
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
                "keyboard.rs",
                "line.rs",
                "shell.rs",
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
    fun k16KernelConsoleKeepsCellGridAndScrolls() {
        val consoleSource = Path.of("../../../rust/guest/k16-kernel/src/console.rs").readText()

        assertTrue(consoleSource.contains("const CELLS_ADDR:"), "kernel console should keep guest cell state")
        assertTrue(consoleSource.contains("fn read_cell("), "kernel console should read cells from guest RAM")
        assertTrue(consoleSource.contains("fn write_cell("), "kernel console should write cells into guest RAM")
        assertTrue(consoleSource.contains("fn scroll_up("), "kernel console should scroll at the bottom row")
        assertTrue(consoleSource.contains("fn repaint_row("), "kernel console should repaint rows from cell state")
        assertFalse(
            consoleSource.contains("else {\n            CURSOR_Y = 0;\n        }"),
            "bottom overflow must scroll instead of wrapping to row zero",
        )
    }

    @Test
    fun k16KernelLineDisciplineKeepsInputBufferOutOfKernelPayload() {
        val lineSource = Path.of("../../../rust/guest/k16-kernel/src/line.rs").readText()

        assertTrue(
            lineSource.contains("const LINE_BUFFER_ADDR:"),
            "line discipline should keep a guest RAM completed-line handoff address",
        )
        assertFalse(
            lineSource.contains("static mut BUFFER: [u8;"),
            "line discipline must not embed the editable input buffer into the K16E payload",
        )
    }

    @Test
    fun k16KernelKeyboardInputGoesThroughLineDiscipline() {
        val kernelSourceDir = Path.of("../../../rust/guest/k16-kernel/src")
        val keyboardSource = kernelSourceDir.resolve("keyboard.rs").readText()

        assertTrue(
            keyboardSource.contains("line::input_byte"),
            "keyboard character input should flow through the kernel line discipline",
        )
        assertFalse(
            keyboardSource.contains("use crate::{console, mmio}"),
            "keyboard.rs must not import the console directly",
        )
        assertFalse(
            keyboardSource.contains("console::write_byte"),
            "keyboard.rs must not echo bytes directly to the console",
        )
    }

    @Test
    fun k16KernelLineDisciplineHandsCompletedLinesToShell() {
        val kernelSourceDir = Path.of("../../../rust/guest/k16-kernel/src")
        val shellPath = kernelSourceDir.resolve("shell.rs")
        val mainSource = kernelSourceDir.resolve("main.rs").readText()
        val lineSource = kernelSourceDir.resolve("line.rs").readText()
        val keyboardSource = kernelSourceDir.resolve("keyboard.rs").readText()

        assertTrue(Files.exists(shellPath), "kernel shell module should exist")
        assertTrue(mainSource.contains("mod shell;"), "main.rs should register the shell module")
        assertTrue(mainSource.contains("shell::init();"), "kernel startup should initialize the shell module")
        assertTrue(lineSource.contains("shell::handle_line"), "line discipline should hand completed lines to shell")
        assertFalse(keyboardSource.contains("shell::"), "keyboard.rs must not call shell directly")
    }

    @Test
    fun bundledK16KernelEchoesKeyboardCharThroughGpuConsole() {
        val workspace = createTempDirectory("k16-keyboard-console-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory.createFromBiosFlash(
            biosFlashPath = biosFlashPath,
            storage0Path = storage0Path,
        ).use { runtime ->
            val control = runThroughBiosSplashAndBoot(runtime)
            assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
            NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

            runtime.pushKeyboardChar('O'.code.toByte())
            val afterInputControl = runtime.tick(maxTurns = 64)
            val frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

            assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
            assertTrue(
                frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixels() },
                "keyboard char input should produce a new visible gpu0 console frame",
            )
        }
    }

    @Test
    fun bundledK16KernelShellHandoffHandlesEnterWithoutPanic() {
        val workspace = createTempDirectory("k16-shell-handoff-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory.createFromBiosFlash(
            biosFlashPath = biosFlashPath,
            storage0Path = storage0Path,
        ).use { runtime ->
            val control = runThroughBiosSplashAndBoot(runtime)
            assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
            NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())

            runtime.pushKeyboardChar('\n'.code.toByte())
            val afterInputControl = runtime.tick(maxTurns = 128)

            assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
            assertEquals(0, afterInputControl.panicCode)
        }
    }

    @Test
    fun bundledK16KernelLineDisciplineHandlesBackspaceAndEnter() {
        val workspace = createTempDirectory("k16-line-discipline-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory.createFromBiosFlash(
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
            val afterInputControl = runtime.tick(maxTurns = 128)
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
    fun bundledK16KernelConsumesKeyboardKeyEventsWithoutPanic() {
        val workspace = createTempDirectory("k16-keyboard-key-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory.createFromBiosFlash(
            biosFlashPath = biosFlashPath,
            storage0Path = storage0Path,
        ).use { runtime ->
            val control = runThroughBiosSplashAndBoot(runtime)
            assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)

            runtime.pushKeyboardKeyDown(key = 65, repeat = false)
            runtime.pushKeyboardKeyUp(key = 65)
            val afterInputControl = runtime.tick(maxTurns = 64)

            assertEquals(NativeK16ComputerControl.STATUS_READY, afterInputControl.status)
            assertEquals(0, afterInputControl.panicCode)
        }
    }

    @Test
    fun bundledK16BiosSplashIsObservableBeforeStorageBoot() {
        val workspace = createTempDirectory("k16-firmware-splash-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory.createFromBiosFlash(
            biosFlashPath = biosFlashPath,
            storage0Path = storage0Path,
        ).use { runtime ->
            val splashControl = runtime.tick()
            val splashSnapshot = runtime.display0Snapshot() ?: error("display0 splash snapshot should exist")
            val splashRow0 = displayRow(splashSnapshot, 0)

            assertEquals(NativeK16ComputerControl.STATUS_BOOTING, splashControl.status)
            assertEquals("K16 BIOS", splashRow0)
            assertFalse("KERNEL OK" in splashRow0)

            val bootControl = runtime.tick(maxTurns = 1_000_000)
            val debug = runtime.outputSnapshot().decodeToString()

            assertEquals(NativeK16ComputerControl.STATUS_READY, bootControl.status)
            assertKernelGpuConsoleVisible(runtime, bootControl, debug)
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
            K16ComputerRuntimeFactory.createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
            ).use { runtime ->
                val control = runThroughBiosSplashAndBoot(runtime)
                assertEquals(NativeK16ComputerControl.STATUS_READY, control.status)
                runtime.machineSnapshot()
            }
        val storage0BeforeRestore = storage0Path.readBytes()

        K16ComputerRuntimeFactory.restoreFromBiosFlashSnapshot(
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

    private fun displayRow(
        snapshot: NativeK16ComputerDisplaySnapshot,
        row: Int,
    ): String {
        val start = row * snapshot.columns
        val end = start + snapshot.columns
        val cells = snapshot.cells.copyOfRange(start, end)
        val visibleEnd =
            cells
                .indexOfLast { it != ' '.code.toByte() && it != 0.toByte() }
                .let { if (it < 0) 0 else it + 1 }
        return cells.copyOfRange(0, visibleEnd).decodeToString()
    }

    private fun DisplayFrameDelta.hasVisiblePixels(): Boolean =
        tiles.any { tile ->
            tile.payload.asSequence().any { it != 0.toByte() }
        }

    private fun assertKernelGpuConsoleVisible(
        runtime: K16ComputerRuntime,
        control: NativeK16ComputerControl,
        debug: String,
    ) {
        val frames = NativeDisplayFrameCodec.decodeFrames(runtime.drainGpu0Frames())
        assertTrue(
            frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixels() },
            "kernel should render visible console pixels through gpu0; frames: ${frames.size}, panic code: ${control.panicCode}, debug: $debug",
        )
        assertTrue(
            frames.any { it.pixelFormat == DisplayPixelFormat.RGB565 && it.hasVisiblePixelsAtOrBelow(globalY = 9) },
            "kernel console should render multiline output below the first text row; frames: ${frames.size}, panic code: ${control.panicCode}, debug: $debug",
        )
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
        val splashControl = runtime.tick()
        assertEquals(NativeK16ComputerControl.STATUS_BOOTING, splashControl.status)
        return runtime.tick(maxTurns = 1_000_000)
    }
}
