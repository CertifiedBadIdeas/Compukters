package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerDisplaySnapshot
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerControl
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16_VOLUME_MAGIC_BYTES
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
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
        assertTrue(source.contains("tasks.register<Exec>(\"linkK16BiosFlash\")"))
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
        assertTrue(source.contains("-C link-arg=--k16-target=bios"))
        assertTrue(source.contains("-C link-arg=--k16-target=boot"))
        assertTrue(source.contains("-C link-arg=--k16-target=kernel"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16BiosObject\")"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16SystemBootObject\")"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileK16SystemKernelObject\")"))
        assertFalse(source.contains("--emit=obj"))
        assertTrue(source.contains("tasks.register<Exec>(\"compileK16SystemStorage0\")"))
        assertTrue(source.contains("rust/guest/k16-bios"))
        assertTrue(source.contains("rust/guest/k16-boot"))
        assertTrue(source.contains("rust/guest/k16-kernel"))
        assertFalse(source.contains("generated/rux-firmware-"))
        assertFalse(source.contains("tasks.register<Exec>(\"compileRux"))
        assertFalse(source.contains("--bin\", \"rux\""))

        val toolchainConfig = Path.of("../../../config/k16-toolchain.json").readText()
        assertTrue(toolchainConfig.contains("\"pin\""))
        assertTrue(toolchainConfig.contains("\"linux-x86_64\""))
        assertTrue(toolchainConfig.contains("\"bin/k16-ld\""))
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
    fun bundledK16BiosFlashShowsNoBootableDeviceWithBundledSystemStorage0Volume() {
        val workspace = createTempDirectory("k16-firmware-resource-test-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        K16ComputerRuntimeFactory.createFromBiosFlash(
            biosFlashPath = biosFlashPath,
            storage0Path = storage0Path,
        ).use { runtime ->
            val control = runtime.tick(maxTurns = 256)
            val snapshot = runtime.display0Snapshot() ?: error("display0 snapshot should exist")

            assertEquals("K16 BIOS", displayRow(snapshot, 0))
            assertEquals("No bootable device", displayRow(snapshot, 2))
            assertEquals(NativeK16ComputerControl.STATUS_HALTED, control.status)
        }
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
}
