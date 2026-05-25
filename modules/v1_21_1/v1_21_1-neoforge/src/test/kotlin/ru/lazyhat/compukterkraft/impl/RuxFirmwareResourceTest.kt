package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxBiosFlashWorkspace
import kotlin.test.Test
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
}
