package ru.lazyhat.compukterkraft.core.gui

import kotlin.test.Test
import kotlin.test.assertEquals

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
}