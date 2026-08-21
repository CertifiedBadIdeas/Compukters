/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukters.core.gui

import ru.lazyhat.compukters.core.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkbenchTerminalMetricsTest {
    @Test
    fun layoutExposesFullTerminalSurfaceAboveStatusBar() {
        val layout =
            WorkbenchTerminalMetrics.layout(
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
        val imageWidth = WorkbenchTerminalMetrics.imageWidth(Config.DEFAULT_COMPUTER_TERM_WIDTH)
        val imageHeight = WorkbenchTerminalMetrics.imageHeight(Config.DEFAULT_COMPUTER_TERM_HEIGHT)
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

    @Test
    fun customTopInsetLetsTerminalStartNearWindowTop() {
        val layout =
            WorkbenchTerminalMetrics.layout(
                leftPos = 0,
                topPos = 0,
                imageWidth = 480,
                imageHeight = 280,
                terminalColumns = 16,
                terminalRows = 8,
                contentTopInset = 8,
            )

        assertEquals(TerminalRect(8, 8, 464, 244), layout.terminalSurfaceBounds)
        assertEquals(TerminalRect(8, 252, 464, 20), layout.statusBounds)
    }
}
