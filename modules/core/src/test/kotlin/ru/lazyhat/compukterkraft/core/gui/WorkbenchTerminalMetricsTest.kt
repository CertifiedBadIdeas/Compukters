package ru.lazyhat.compukterkraft.core.gui

import ru.lazyhat.compukterkraft.core.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkbenchTerminalMetricsTest {
    @Test
    fun layoutExposesFullTerminalSurfaceAboveStatusBar() {
        val layout = WorkbenchTerminalMetrics.layout(
            leftPos = 0,
            topPos = 0,
            imageWidth = 480,
            imageHeight = 280,
            terminalColumns = 16,
            terminalRows = 8,
        )

        assertEquals(TerminalRect(8, 34, 464, 218), layout.terminalSurfaceBounds)
        assertEquals(TerminalRect(8, 34, 96, 72), layout.terminalBounds)
        assertEquals(TerminalRect(8, 252, 464, 20), layout.statusBounds)
    }

    @Test
    fun defaultComputerTerminalNearlyFillsWorkbenchSurface() {
        val imageWidth = WorkbenchTerminalMetrics.imageWidth(Config.DEFAULT_COMPUTER_TERM_WIDTH, Config.DEFAULT_COMPUTER_TERM_HEIGHT)
        val imageHeight = WorkbenchTerminalMetrics.imageHeight(Config.DEFAULT_COMPUTER_TERM_WIDTH, Config.DEFAULT_COMPUTER_TERM_HEIGHT)
        val layout =
            WorkbenchTerminalMetrics.layout(
                leftPos = 0,
                topPos = 0,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                terminalColumns = Config.DEFAULT_COMPUTER_TERM_WIDTH,
                terminalRows = Config.DEFAULT_COMPUTER_TERM_HEIGHT,
            )

        assertTrue(layout.terminalSurfaceBounds.width - layout.terminalBounds.width < TerminalFontConstants.FONT_WIDTH)
        assertTrue(layout.terminalSurfaceBounds.height - layout.terminalBounds.height < TerminalFontConstants.FONT_HEIGHT)
    }
}