package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuxFirmwareResourceTest {
    @Test
    fun bundledRuxTerminalFirmwareResourceExists() {
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-terminal.ruxi"),
                "firmware/rux-terminal.ruxi must be bundled",
            ).use { it.readBytes() }

        assertTrue(bytes.size > 8, "Rux firmware image should not be empty")
        assertTrue(
            bytes.copyOfRange(0, 4).contentEquals(
                byteArrayOf('R'.code.toByte(), 'U'.code.toByte(), 'X'.code.toByte(), 'I'.code.toByte()),
            ),
        )
    }

    @Test
    fun bundledRuxTerminalFirmwareBootsOnNativeComputerWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("rux.vm.native.library")?.takeIf { it.isNotBlank() } ?: return
        val bytes =
            assertNotNull(
                javaClass.classLoader.getResourceAsStream("firmware/rux-terminal.ruxi"),
                "firmware/rux-terminal.ruxi must be bundled",
            ).use { it.readBytes() }

        val handle =
            NativeVmBindings.createRuxComputer(
                libraryPath = libraryPath,
                image = bytes,
                memorySize = 64 * 1024,
                sliceBudgetNanos = 1_000_000,
            )

        try {
            NativeVmBindings.runRuxComputerUntilSignal(handle)
            val output = NativeVmBindings.ruxComputerDebugOutput(handle).decodeToString()
            assertTrue(output.contains("RUX"), output)
            assertEquals(3, NativeVmBindings.ruxComputerControl(handle).status)
        } finally {
            NativeVmBindings.freeRuxComputer(handle)
        }
    }
}
