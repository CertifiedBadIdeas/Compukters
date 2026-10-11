/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalState
import ru.lazyhat.compukters.impl.terminal.TerminalGridGeometry
import ru.lazyhat.compukters.impl.terminal.TerminalReplica
import ru.lazyhat.compukters.impl.terminal.TerminalSurfaceStyle
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeWindowSurfaceTest {
    @Test
    fun `rounded window leaves corners untouched and keeps a one pixel border`() {
        val canvas = RecordingCanvas()
        val bounds = IdeRect(10, 20, 50, 50)
        IdeWindowSurface.paint(canvas, bounds, IdeColors.PANEL_ALT)
        assertNull(canvas.pixel(10, 20))
        assertEquals(IdeColors.WINDOW_BORDER, canvas.pixel(30, 20))
        assertEquals(IdeColors.WINDOW_BORDER, canvas.pixel(10, 35))
        assertEquals(IdeColors.PANEL_ALT, canvas.pixel(30, 21))
        assertEquals(IdeColors.PANEL_ALT, canvas.pixel(11, 35))
        assertTrue(canvas.fills.all { bounds.contains(it.first) })
        for (width in 0..8) {
            for (height in 0..8) {
                val small = RecordingCanvas()
                val rect = IdeRect(0, 0, width, height)
                IdeWindowSurface.paint(small, rect, IdeColors.PANEL_ALT)
                assertTrue(small.fills.all { rect.contains(it.first) && it.first.width > 0 && it.first.height > 0 })
            }
        }
    }

    @Test
    fun `terminal shares window chrome font metrics and focus color without altering guest cells`() {
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, IdeCodeFontProfile.DEFAULT)
        val overlay = IdeTerminalOverlayGeometry.compute(geometry.content)
        val terminal = TerminalState(42, 51, 19, List(51 * 19) { TerminalCell('A'.code, 15, 0) }, TerminalPosition(0, 0), true)
        val active = IdeTargetTerminalState.Active(UUID(0, 1), 7, TerminalReplica(terminal))
        for (focused in listOf(false, true)) {
            val canvas = RecordingCanvas()
            val frame =
                IdeScreenFrame(
                    CompuktersUiViewport.admitIde(1920, 1080, 2),
                    geometry,
                    IdeDrawBuilder(geometry.font).build(),
                    active,
                    true,
                    focused,
                )
            IdeFramePainter.paint(frame, canvas, 123)
            val title = canvas.labels.single()
            assertEquals(IdeColors.TEXT, title.color)
            assertEquals(geometry.font.centeredCellTop(overlay.title) + geometry.font.glyphDrawOffsetY, title.y)
            assertEquals(
                if (focused) IdeColors.ACCENT else IdeColors.WINDOW_BORDER,
                canvas.pixel(overlay.panel.left + overlay.panel.width / 2, overlay.panel.top),
            )
            assertEquals(TerminalSurfaceStyle.BACKGROUND_COLOR, canvas.pixel(overlay.panel.left + 8, overlay.panel.top + 8))
            assertEquals(terminal, canvas.terminalState)
            val grid = requireNotNull(overlay.grid)
            assertEquals(TerminalSurfaceStyle.BACKGROUND_COLOR, canvas.pixel(grid.left, grid.top))
            assertEquals(TerminalSurfaceStyle.BACKGROUND_COLOR, canvas.pixel(grid.right - 1, grid.bottom - 1))
            val rendered = requireNotNull(canvas.terminalGeometry).bounds
            assertEquals(
                listOf(grid.left, grid.top, grid.right, grid.bottom),
                listOf(rendered.left, rendered.top, rendered.right, rendered.bottom),
            )
        }
    }

    private class RecordingCanvas : IdeCanvas {
        val fills = mutableListOf<Pair<IdeRect, Int>>()
        val labels = mutableListOf<IdeTextDraw>()
        var terminalState: TerminalState? = null
        var terminalGeometry: TerminalGridGeometry? = null

        override fun fill(
            bounds: IdeRect,
            color: Int,
        ) {
            fills += bounds to color
        }

        override fun icon(draw: IdeIconDraw) = Unit

        override fun text(draw: IdeTextDraw) {
            labels += draw
        }

        override fun terminal(
            state: TerminalState,
            geometry: TerminalGridGeometry,
            nowMillis: Long,
        ) {
            terminalState = state
            terminalGeometry = geometry
        }

        fun pixel(
            x: Int,
            y: Int,
        ): Int? = fills.lastOrNull { (bounds, _) -> x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom }?.second
    }
}
