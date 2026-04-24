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
package ru.lazyhat.compukterkraft.core.gui

import kotlin.math.max

data class TerminalRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    fun contains(
        pointX: Int,
        pointY: Int,
    ): Boolean = pointX >= x && pointY >= y && pointX < x + width && pointY < y + height
}

data class WorkbenchTerminalLayout(
    val panelBounds: TerminalRect,
    val terminalSurfaceBounds: TerminalRect,
    val terminalBounds: TerminalRect,
    val statusBounds: TerminalRect,
)

/**
 * Computes terminal panel metrics from terminal grid dimensions (columns × rows).
 * No dependency on [Terminal] — works purely with width/height integers.
 */
object WorkbenchTerminalMetrics {
    private const val MIN_IMAGE_WIDTH = 480
    private const val MIN_IMAGE_HEIGHT = 280
    private const val OUTER_PADDING = 8
    private const val DEFAULT_CONTENT_TOP = 34
    private const val STATUS_HEIGHT = 20

    fun imageWidth(terminalColumns: Int): Int = max(terminalPixelWidth(terminalColumns) + OUTER_PADDING * 2, MIN_IMAGE_WIDTH)

    fun imageHeight(
        terminalRows: Int,
        contentTopInset: Int = DEFAULT_CONTENT_TOP,
    ): Int = max(terminalPixelHeight(terminalRows) + STATUS_HEIGHT + contentTopInset + OUTER_PADDING, MIN_IMAGE_HEIGHT)

    fun layout(
        leftPos: Int,
        topPos: Int,
        imageWidth: Int,
        imageHeight: Int,
        terminalColumns: Int,
        terminalRows: Int,
        contentTopInset: Int = DEFAULT_CONTENT_TOP,
    ): WorkbenchTerminalLayout {
        val panelBounds =
            TerminalRect(
                leftPos + OUTER_PADDING,
                topPos + contentTopInset,
                imageWidth - OUTER_PADDING * 2,
                imageHeight - contentTopInset - OUTER_PADDING,
            )
        val statusBounds =
            TerminalRect(
                panelBounds.x,
                panelBounds.y + panelBounds.height - STATUS_HEIGHT,
                panelBounds.width,
                STATUS_HEIGHT,
            )
        val terminalSurfaceBounds =
            TerminalRect(
                panelBounds.x,
                panelBounds.y,
                panelBounds.width,
                statusBounds.y - panelBounds.y,
            )

        val terminalWidth = terminalPixelWidth(terminalColumns)
        val terminalHeight = terminalPixelHeight(terminalRows)
        val terminalX = terminalSurfaceBounds.x
        val terminalY = terminalSurfaceBounds.y

        return WorkbenchTerminalLayout(
            panelBounds = panelBounds,
            terminalSurfaceBounds = terminalSurfaceBounds,
            terminalBounds = TerminalRect(terminalX, terminalY, terminalWidth, terminalHeight),
            statusBounds = statusBounds,
        )
    }

    private fun terminalPixelWidth(columns: Int): Int = columns * TerminalFontConstants.FONT_WIDTH

    private fun terminalPixelHeight(rows: Int): Int = rows * TerminalFontConstants.FONT_HEIGHT
}
