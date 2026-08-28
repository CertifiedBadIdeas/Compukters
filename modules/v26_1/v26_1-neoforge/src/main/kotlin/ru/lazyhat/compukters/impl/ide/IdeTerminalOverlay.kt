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
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import ru.lazyhat.compukters.impl.terminal.TerminalProtocol

internal data class IdeTerminalOverlayGeometry(
    val panel: IdeRect,
    val shadow: IdeRect,
    val title: IdeRect,
    val grid: IdeRect?,
    val status: IdeRect,
    val messageBounds: IdeRect,
    val supported: Boolean,
    val unsupportedMessage: String,
) {
    companion object {
        fun compute(
            content: IdeRect,
            font: TerminalFontProfile,
        ): IdeTerminalOverlayGeometry {
            val gridWidth = TerminalProtocol.WIDTH * font.cellWidth
            val gridHeight = TerminalProtocol.HEIGHT * font.cellHeight
            val preferredWidth = gridWidth + BORDER_SIZE * 2 + GRID_PADDING * 2
            val preferredHeight =
                gridHeight + BORDER_SIZE * 2 + TITLE_HEIGHT + STATUS_HEIGHT + GRID_PADDING * 2
            if (content.width < preferredWidth || content.height < preferredHeight) {
                val panel = content
                return IdeTerminalOverlayGeometry(
                    panel = panel,
                    shadow = IdeRect(panel.left, panel.top, panel.left, panel.bottom),
                    title = panel,
                    grid = null,
                    status = panel,
                    messageBounds = inset(panel, MESSAGE_PADDING),
                    supported = false,
                    unsupportedMessage = UNSUPPORTED_MESSAGE,
                )
            }
            val left = (content.right - preferredWidth).coerceAtLeast(content.left)
            val top = (content.top + (content.height - preferredHeight) / 2).coerceAtLeast(content.top)
            val panel = IdeRect(left, top, left + preferredWidth, top + preferredHeight)
            val innerLeft = panel.left + BORDER_SIZE
            val innerRight = panel.right - BORDER_SIZE
            val title = IdeRect(innerLeft, panel.top + BORDER_SIZE, innerRight, panel.top + BORDER_SIZE + TITLE_HEIGHT)
            val gridTop = title.bottom + GRID_PADDING
            val grid =
                IdeRect(
                    innerLeft + GRID_PADDING,
                    gridTop,
                    innerLeft + GRID_PADDING + gridWidth,
                    gridTop + gridHeight,
                )
            val status = IdeRect(innerLeft, grid.bottom + GRID_PADDING, innerRight, panel.bottom - BORDER_SIZE)
            return IdeTerminalOverlayGeometry(
                panel = panel,
                shadow = IdeRect(panel.left - SHADOW_WIDTH, panel.top, panel.left, panel.bottom),
                title = title,
                grid = grid,
                status = status,
                messageBounds = grid,
                supported = true,
                unsupportedMessage = UNSUPPORTED_MESSAGE,
            )
        }

        private fun inset(
            bounds: IdeRect,
            amount: Int,
        ): IdeRect {
            val horizontal = minOf(amount, bounds.width / 2)
            val vertical = minOf(amount, bounds.height / 2)
            return IdeRect(
                bounds.left + horizontal,
                bounds.top + vertical,
                bounds.right - horizontal,
                bounds.bottom - vertical,
            )
        }

        private const val BORDER_SIZE = 1
        private const val GRID_PADDING = 6
        private const val TITLE_HEIGHT = 18
        private const val STATUS_HEIGHT = 14
        private const val SHADOW_WIDTH = 2
        private const val MESSAGE_PADDING = 8
        private const val UNSUPPORTED_MESSAGE = "Viewport is too small for the target terminal"
    }
}
