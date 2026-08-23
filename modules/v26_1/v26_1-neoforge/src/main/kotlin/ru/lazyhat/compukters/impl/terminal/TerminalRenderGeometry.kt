/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

class TerminalRenderGeometry(
    viewportWidth: Int,
    viewportHeight: Int,
    private val fontProfile: TerminalFontProfile = TerminalFontProfile.DEFAULT,
) {
    init {
        require(viewportWidth >= 0 && viewportHeight >= 0) { "terminal viewport must not be negative" }
    }

    val columns: Int = COLUMNS
    val rows: Int = ROWS
    val gridWidth: Int = columns * fontProfile.cellWidth
    val gridHeight: Int = rows * fontProfile.cellHeight
    val panelWidth: Int = gridWidth + PANEL_PADDING * 2
    val panelHeight: Int = TITLE_HEIGHT + gridHeight + PANEL_PADDING
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
            panel.bottom - PANEL_PADDING,
        )
    val originX: Int = grid.left
    val originY: Int = grid.top
    val titleX: Int = panel.left + PANEL_PADDING
    val titleY: Int = panel.top + TITLE_TOP

    fun cell(
        x: Int,
        y: Int,
    ): TerminalRect {
        require(x in 0 until columns && y in 0 until rows) { "terminal cell is outside the grid" }
        val left = originX + x * fontProfile.cellWidth
        val top = originY + y * fontProfile.cellHeight
        return TerminalRect(left, top, left + fontProfile.cellWidth, top + fontProfile.cellHeight)
    }

    fun glyphClip(
        x: Int,
        y: Int,
    ): TerminalRect = cell(x, y)

    fun cursor(position: TerminalPosition): TerminalRect {
        val cell = cell(position.x, position.y)
        return TerminalRect(cell.left, cell.bottom - CURSOR_HEIGHT, cell.right, cell.bottom)
    }

    companion object {
        const val COLUMNS = 51
        const val ROWS = 19
        const val PANEL_PADDING = 8
        const val TITLE_HEIGHT = 18
        const val TITLE_TOP = 5
        private const val CURSOR_HEIGHT = 1
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
