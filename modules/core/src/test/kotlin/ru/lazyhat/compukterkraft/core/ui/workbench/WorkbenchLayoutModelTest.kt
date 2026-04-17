/*
 * The Compukter Kraft Developers
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
package ru.lazyhat.compukterkraft.core.ui.workbench

import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkbenchLayoutModelTest {
    private val fontMetrics = FontMetrics { it.length * 6 }

    @Test
    fun fullscreenLayoutAllocatesHeaderSidebarEditorAndStatusBar() {
        val layout = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)

        assertEquals(UiRect(0, 0, 1280, 32), layout.headerBounds)
        assertTrue(layout.sidebarBounds.width > 0)
        assertTrue(layout.editorBounds.height > 0)
        assertEquals(20, layout.statusBarBounds.height)
    }

    @Test
    fun visibleTerminalDockReducesEditorHeight() {
        val hidden = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)
        val shown = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, true, fontMetrics)

        assertTrue(shown.editorBounds.height < hidden.editorBounds.height)
        assertTrue(shown.terminalDockBounds!!.height > 0)
    }

    @Test
    fun fullscreenLayoutExposesTargetSlotAndDockToggleArea() {
        val layout = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)

        assertTrue(layout.targetSlotBounds.width > 0)
        assertTrue(layout.headerBounds.height > 0)
        assertTrue(layout.terminalToggleBounds.width > 0)
    }

    @Test
    fun fullscreenLayoutKeepsHeaderControlsSeparated() {
        val layout = WorkbenchLayoutModel.fullscreen(0, 0, 1280, 720, false, fontMetrics)

        assertFalse(layout.terminalToggleBounds.overlaps(layout.rebootBounds))
        assertFalse(layout.rebootBounds.overlaps(layout.targetSlotBounds))
    }

    private fun UiRect.overlaps(other: UiRect): Boolean =
        x < other.right && right > other.x && y < other.bottom && bottom > other.y
}