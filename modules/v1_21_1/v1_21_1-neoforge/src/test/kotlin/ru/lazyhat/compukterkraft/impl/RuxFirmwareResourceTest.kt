package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeRuxComputerDisplaySnapshot
import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxBiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.storage.RUX_VOLUME_MAGIC_BYTES
import ru.lazyhat.compukterkraft.lang.runtime.storage.RuxSystemVolumeWorkspace
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuxFirmwareResourceTest {
    @Test
    fun bundledRux16BiosFlashResourceExists() {
        val bytes =
            RuxBiosFlashWorkspace.loadBiosFlashResource(
                classLoader = javaClass.classLoader,
            )

        assertTrue(bytes.size > 8, "Rux16 BIOS flash resource should not be empty")
        assertEquals(0, bytes.size % 2, "Rux16 BIOS flash resource should contain whole words")
    }

    @Test
    fun bundledRux16BiosFlashResourceIsRawFlash() {
        val resource = javaClass.classLoader.getResourceAsStream("firmware/rux16-bios.flash")
            ?: error("raw Rux16 BIOS flash resource should exist")

        val bytes = resource.use { it.readBytes() }
        assertContentEquals(
            bytes,
            RuxBiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader),
        )
    }

    @Test
    fun bundledRux16SystemStorage0VolumeResourceExists() {
        val bytes =
            RuxSystemVolumeWorkspace.loadStorage0VolumeResource(
                classLoader = javaClass.classLoader,
            )

        assertTrue(bytes.size > 512, "Rux16 system storage0 volume resource should not be empty")
        assertContentEquals(RUX_VOLUME_MAGIC_BYTES, bytes.copyOfRange(0, RUX_VOLUME_MAGIC_BYTES.size))
    }

    @Test
    fun bundledRux16BiosFlashBootsBundledSystemStorage0Volume() {
        val workspace = createTempDirectory("rux-firmware-resource-test-")
        val biosFlashPath = workspace.resolve("bios.flash")
        val storage0Path = workspace.resolve("storage0.ruxvol")
        biosFlashPath.writeBytes(RuxBiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(RuxSystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))

        RuxComputerRuntimeFactory.createFromBiosFlash(
            biosFlashPath = biosFlashPath,
            storage0Path = storage0Path,
        ).use { runtime ->
            val control = runtime.tick(maxTurns = 64)
            val snapshot = runtime.display0Snapshot() ?: error("display0 snapshot should exist")

            assertEquals("KERNEL OK", displayRow(snapshot, 0))
            assertEquals(75, control.panicCode)
        }
    }

    private fun displayRow(
        snapshot: NativeRuxComputerDisplaySnapshot,
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
