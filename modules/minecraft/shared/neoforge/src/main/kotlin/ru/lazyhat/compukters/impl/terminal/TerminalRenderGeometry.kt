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

package ru.lazyhat.compukters.impl.terminal

import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition

data class TerminalRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top
}

class TerminalGridGeometry(
    originX: Int,
    originY: Int,
) {
    val columns: Int = TerminalRenderGeometry.COLUMNS
    val rows: Int = TerminalRenderGeometry.ROWS
    val bounds =
        TerminalRect(
            originX,
            originY,
            originX + columns * TerminalFontProfile.cellWidth,
            originY + rows * TerminalFontProfile.cellHeight,
        )

    fun cell(
        x: Int,
        y: Int,
    ): TerminalRect {
        require(x in 0 until columns && y in 0 until rows) { "terminal cell is outside the grid" }
        val left = bounds.left + x * TerminalFontProfile.cellWidth
        val top = bounds.top + y * TerminalFontProfile.cellHeight
        return TerminalRect(left, top, left + TerminalFontProfile.cellWidth, top + TerminalFontProfile.cellHeight)
    }

    val glyphClip: TerminalRect
        get() =
            // ScreenRectangle floors the transformed width and height independently. One cell of
            // positive-edge slack keeps the final grid edge inside the scissor at fractional scales.
            TerminalRect(
                bounds.left,
                bounds.top,
                bounds.right + TerminalFontProfile.cellWidth,
                bounds.bottom + TerminalFontProfile.cellHeight,
            )

    fun cursor(position: TerminalPosition): TerminalRect {
        val cell = cell(position.x, position.y)
        return TerminalRect(cell.left, cell.bottom - 1, cell.right, cell.bottom)
    }
}

class TerminalRenderGeometry(
    viewportWidth: Int,
    viewportHeight: Int,
) {
    init {
        require(viewportWidth >= 0 && viewportHeight >= 0) { "terminal viewport must not be negative" }
    }

    val columns: Int = COLUMNS
    val rows: Int = ROWS
    val gridWidth: Int = columns * TerminalFontProfile.cellWidth
    val gridHeight: Int = rows * TerminalFontProfile.cellHeight
    val panelWidth: Int = gridWidth + PANEL_PADDING * 2
    val panelHeight: Int = TITLE_HEIGHT + gridHeight + FOOTER_GAP + FOOTER_HEIGHT + PANEL_PADDING
    val panel: TerminalRect =
        TerminalRect(
            (viewportWidth - panelWidth) / 2,
            (viewportHeight - panelHeight) / 2,
            (viewportWidth - panelWidth) / 2 + panelWidth,
            (viewportHeight - panelHeight) / 2 + panelHeight,
        )
    val grid: TerminalRect =
        TerminalRect(
            panel.left + PANEL_PADDING,
            panel.top + TITLE_HEIGHT,
            panel.right - PANEL_PADDING,
            panel.top + TITLE_HEIGHT + gridHeight,
        )
    val footer: TerminalRect =
        TerminalRect(
            grid.left,
            grid.bottom + FOOTER_GAP,
            grid.right,
            grid.bottom + FOOTER_GAP + FOOTER_HEIGHT,
        )
    val gridGeometry = TerminalGridGeometry(grid.left, grid.top)
    val originX: Int = grid.left
    val originY: Int = grid.top
    val titleX: Int = panel.left + PANEL_PADDING
    val titleY: Int = panel.top + TITLE_TOP
    val ideButton: TerminalRect =
        TerminalRect(
            panel.right - PANEL_PADDING - IDE_BUTTON_WIDTH,
            panel.top + (TITLE_HEIGHT - IDE_BUTTON_HEIGHT) / 2,
            panel.right - PANEL_PADDING,
            panel.top + (TITLE_HEIGHT - IDE_BUTTON_HEIGHT) / 2 + IDE_BUTTON_HEIGHT,
        )

    fun cell(
        x: Int,
        y: Int,
    ): TerminalRect = gridGeometry.cell(x, y)

    val glyphClip: TerminalRect
        get() = gridGeometry.glyphClip

    fun cursor(position: TerminalPosition): TerminalRect = gridGeometry.cursor(position)

    companion object {
        const val COLUMNS = 51
        const val ROWS = 19
        const val PANEL_PADDING = 8
        const val TITLE_HEIGHT = 18
        const val TITLE_TOP = 5
        const val FOOTER_GAP = 2
        const val FOOTER_HEIGHT = 13
        private const val IDE_BUTTON_HEIGHT = 14
        private const val IDE_BUTTON_WIDTH = 76
        private const val CURSOR_HALF_PERIOD_MILLISECONDS = 500L
        private val PALETTE =
            intArrayOf(
                0xFF000000.toInt(),
                0xFFAA0000.toInt(),
                0xFF00AA00.toInt(),
                0xFFAA5500.toInt(),
                0xFF0000AA.toInt(),
                0xFFAA00AA.toInt(),
                0xFF00AAAA.toInt(),
                0xFFAAAAAA.toInt(),
                0xFF555555.toInt(),
                0xFFFF5555.toInt(),
                0xFF55FF55.toInt(),
                0xFFFFFF55.toInt(),
                0xFF5555FF.toInt(),
                0xFFFF55FF.toInt(),
                0xFF55FFFF.toInt(),
                0xFFFFFFFF.toInt(),
            )

        fun paletteColor(index: Int): Int {
            require(index in PALETTE.indices) { "terminal palette index is outside the palette" }
            return PALETTE[index]
        }

        fun drawCursor(
            authoritativeVisible: Boolean,
            milliseconds: Long,
        ): Boolean =
            authoritativeVisible &&
                milliseconds >= 0 &&
                milliseconds / CURSOR_HALF_PERIOD_MILLISECONDS % 2L == 0L
    }
}
