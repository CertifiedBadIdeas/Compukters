package ru.lazyhat.compukterkraft.core.gui

import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerTerminalDefaultsTest {
    @Test
    fun normalComputerFallbackSnapshotUsesConfiguredDimensionsWithoutColour() {
        val snapshot = ComputerTerminalDefaults.fallbackSnapshot(ComputerFamily.NORMAL)

        assertEquals(Config.DEFAULT_COMPUTER_TERM_WIDTH, snapshot.width)
        assertEquals(Config.DEFAULT_COMPUTER_TERM_HEIGHT, snapshot.height)
        assertFalse(snapshot.colour)
    }

    @Test
    fun advancedComputerFallbackSnapshotUsesConfiguredDimensionsWithColour() {
        val snapshot = ComputerTerminalDefaults.fallbackSnapshot(ComputerFamily.ADVANCED)

        assertEquals(Config.DEFAULT_COMPUTER_TERM_WIDTH, snapshot.width)
        assertEquals(Config.DEFAULT_COMPUTER_TERM_HEIGHT, snapshot.height)
        assertTrue(snapshot.colour)
    }
}