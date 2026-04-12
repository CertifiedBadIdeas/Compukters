package ru.lazyhat.compukterkraft.core.ui.dsl

import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalUiBuilderTest {
    private val snapshot = ScreenBufferSnapshot.empty(width = 16, height = 8, colour = true)
    private val layout = WorkbenchTerminalMetrics.layout(0, 0, 480, 280, 16, 8)

    @Test
    fun poweredOffViewShowsPlaceholderWithoutTerminalTitleOrFocusHint() {
        val nodes = buildTerminalUi(
            leftPos = 0,
            topPos = 0,
            imageWidth = 480,
            imageHeight = 280,
            layout = layout,
            snapshot = snapshot,
            focused = false,
            poweredOn = false,
            showFocusHint = false,
            placeholderText = "Computer is off. Turn it on first.",
        )

        val texts = nodes.filterIsInstance<Text>().map { it.text }

        assertTrue("Computer is off. Turn it on first." in texts)
        assertFalse("Terminal" in texts)
        assertFalse("Click terminal to focus input" in texts)
        assertTrue(nodes.none { it is TerminalView })
    }

    @Test
    fun activeViewShowsFocusHintOnlyWhenTerminalIsUnfocused() {
        val nodes = buildTerminalUi(
            leftPos = 0,
            topPos = 0,
            imageWidth = 480,
            imageHeight = 280,
            layout = layout,
            snapshot = snapshot,
            focused = false,
            poweredOn = true,
            showFocusHint = true,
            placeholderText = "Computer is off. Turn it on first.",
        )

        val texts = nodes.filterIsInstance<Text>().map { it.text }

        assertTrue("Click terminal to focus input" in texts)
        assertFalse("Computer is off. Turn it on first." in texts)
        assertTrue(nodes.any { it is TerminalView })
    }
}