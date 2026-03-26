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
package ck.mod.gui

import com.mojang.blaze3d.vertex.Tesselator
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.MultiBufferSource

object WorkbenchTerminalRenderer {
    private const val WINDOW_BACKGROUND = 0xFF12151D.toInt()
    private const val PANEL_BACKGROUND = 0xFF0D1016.toInt()
    private const val PANEL_BORDER = 0xFF1D2330.toInt()
    private const val STATUS_BACKGROUND = 0xFF161B25.toInt()
    private const val TERMINAL_BACKGROUND = 0xFF05070B.toInt()
    private const val TERMINAL_BORDER = 0xFF222938.toInt()
    private const val TERMINAL_BORDER_FOCUSED = 0xFF4883C7.toInt()
    private const val TITLE_COLOR = 0xE6ECF5
    private const val MUTED_TEXT = 0x9CA8B8

    fun render(
        graphics: GuiGraphics,
        font: Font,
        leftPos: Int,
        topPos: Int,
        imageWidth: Int,
        imageHeight: Int,
        layout: WorkbenchTerminalLayout,
        terminal: Terminal,
        focused: Boolean,
    ) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, WINDOW_BACKGROUND)
        graphics.fill(
            layout.panelBounds.x,
            layout.panelBounds.y,
            layout.panelBounds.x + layout.panelBounds.width,
            layout.panelBounds.y + layout.panelBounds.height,
            PANEL_BACKGROUND,
        )
        graphics.fill(
            layout.panelBounds.x,
            layout.panelBounds.y,
            layout.panelBounds.x + layout.panelBounds.width,
            layout.panelBounds.y + 1,
            PANEL_BORDER,
        )
        graphics.fill(
            layout.statusBounds.x,
            layout.statusBounds.y,
            layout.statusBounds.x + layout.statusBounds.width,
            layout.statusBounds.y + layout.statusBounds.height,
            STATUS_BACKGROUND,
        )

        val borderColour = if (focused) TERMINAL_BORDER_FOCUSED else TERMINAL_BORDER
        graphics.fill(
            layout.terminalBounds.x - 1,
            layout.terminalBounds.y - 1,
            layout.terminalBounds.x + layout.terminalBounds.width + 1,
            layout.terminalBounds.y + layout.terminalBounds.height + 1,
            borderColour,
        )
        graphics.fill(
            layout.terminalBounds.x,
            layout.terminalBounds.y,
            layout.terminalBounds.x + layout.terminalBounds.width,
            layout.terminalBounds.y + layout.terminalBounds.height,
            TERMINAL_BACKGROUND,
        )

        graphics.drawString(font, "Terminal", layout.panelBounds.x + 12, layout.panelBounds.y + 8, TITLE_COLOR, false)

        val statusText = if (focused) "Input active  |  Ctrl+V paste" else "Click terminal to focus input"
        graphics.drawString(font, statusText, layout.statusBounds.x + 12, layout.statusBounds.y + 6, MUTED_TEXT, false)
        val sizeText = "${terminal.width} x ${terminal.height}"
        graphics.drawString(
            font,
            sizeText,
            layout.statusBounds.x + layout.statusBounds.width - 12 - font.width(sizeText),
            layout.statusBounds.y + 6,
            MUTED_TEXT,
            false,
        )

        val bufferSource = MultiBufferSource.immediate(Tesselator.getInstance().builder)
        val emitter = FixedWidthFontRenderer.toVertexConsumer(graphics.pose(), bufferSource.getBuffer(RenderTypes.TERMINAL))
        FixedWidthFontRenderer.drawTerminal(
            emitter,
            layout.terminalBounds.x.toFloat(),
            layout.terminalBounds.y.toFloat(),
            terminal,
            0f,
            0f,
            0f,
            0f,
        )
        bufferSource.endBatch()
    }
}
