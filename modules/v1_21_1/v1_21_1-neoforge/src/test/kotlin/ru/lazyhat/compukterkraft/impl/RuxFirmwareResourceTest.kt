package ru.lazyhat.compukterkraft.impl

import kotlin.test.Test
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
}
