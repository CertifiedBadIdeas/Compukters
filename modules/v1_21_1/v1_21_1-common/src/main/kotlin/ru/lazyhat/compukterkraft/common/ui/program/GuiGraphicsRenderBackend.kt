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

package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import ru.lazyhat.compukterkraft.core.ui.editor.CodeEditorMetrics
import ru.lazyhat.compukterkraft.core.ui.editor.EditorViewModel
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.program.RenderBackend
import ru.lazyhat.compukterkraft.core.workbench.highlightColor
import ru.lazyhat.compukterkraft.lang.runtime.IdeDiagnosticSeverity
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

class GuiGraphicsRenderBackend(
    private val graphics: GuiGraphics,
    private val font: Font,
) : RenderBackend {
    private data class Clip(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private val clipStack = ArrayDeque<Clip>()

    override fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Color,
    ) {
        graphics.fill(x, y, x + width, y + height, color.value.toInt())
    }

    override fun drawText(
        x: Int,
        y: Int,
        text: String,
        color: Color,
    ) {
        graphics.drawString(font, text, x, y, color.value.toInt(), false)
    }

    override fun drawTerminalSurface(
        x: Int,
        y: Int,
        snapshot: ScreenBufferSnapshot,
    ) {
        graphics.fill(x - 1, y - 1, x + 1, y + 1, 0xFF222938.toInt())
        TerminalSurfaceBridge.draw(graphics, x, y, snapshot)
    }

    override fun pushClip(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        // Intersect with the current clip so nested ScrollAreas behave correctly.
        val parent = clipStack.lastOrNull()
        val clip =
            if (parent == null) {
                Clip(x, y, width, height)
            } else {
                val nx = maxOf(parent.x, x)
                val ny = maxOf(parent.y, y)
                val nx2 = minOf(parent.x + parent.width, x + width)
                val ny2 = minOf(parent.y + parent.height, y + height)
                Clip(nx, ny, (nx2 - nx).coerceAtLeast(0), (ny2 - ny).coerceAtLeast(0))
            }
        clipStack.addLast(clip)
        graphics.enableScissor(clip.x, clip.y, clip.x + clip.width, clip.y + clip.height)
    }

    override fun popClip() {
        if (clipStack.isEmpty()) return
        clipStack.removeLast()
        val parent = clipStack.lastOrNull()
        if (parent == null) {
            graphics.disableScissor()
        } else {
            graphics.enableScissor(parent.x, parent.y, parent.x + parent.width, parent.y + parent.height)
        }
    }

    override fun drawCodeEditor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        viewModel: EditorViewModel,
        fontWidth: Int,
        fontHeight: Int,
    ) {
        val text = viewModel.text
        val lines = if (text.isEmpty()) listOf("") else text.split('\n')
        val totalLines = lines.size
        val gutterWidth = CodeEditorMetrics.gutterPixelWidth(totalLines, fontWidth)
        val visibleLines = (height / fontHeight).coerceAtLeast(1)
        val startLine = viewModel.scrollLine.coerceAtLeast(0)
        val endLine = minOf(totalLines, startLine + visibleLines)

        // Background and gutter background.
        graphics.fill(x, y, x + width, y + height, 0xFF1E2433.toInt())
        graphics.fill(x, y, x + gutterWidth, y + height, 0xFF161B26.toInt())

        // Cursor row highlight.
        val cursorVisibleRow = viewModel.cursorLine - startLine
        if (cursorVisibleRow in 0 until visibleLines) {
            val rowY = y + cursorVisibleRow * fontHeight
            graphics.fill(x + gutterWidth, rowY, x + width, rowY + fontHeight, 0x33294055)
        }

        pushClip(x, y, width, height)
        try {
            val highlights = viewModel.highlights
            for (lineIndex in startLine until endLine) {
                val drawY = y + (lineIndex - startLine) * fontHeight
                graphics.drawString(
                    font,
                    (lineIndex + 1).toString(),
                    x + 2,
                    drawY,
                    0xFF7D899C.toInt(),
                    false,
                )

                val lineText = lines[lineIndex]
                val tokens =
                    highlights.filter {
                        it.range.start.line == lineIndex && it.range.end.line == lineIndex
                    }

                val baseTextX = x + gutterWidth
                if (tokens.isEmpty()) {
                    graphics.drawString(font, lineText, baseTextX, drawY, 0xFFE6ECF5.toInt(), false)
                } else {
                    var drawX = baseTextX
                    var column = 0
                    tokens.sortedBy { it.range.start.column }.forEach { token ->
                        val start =
                            token.range.start.column
                                .coerceIn(0, lineText.length)
                        val end =
                            token.range.end.column
                                .coerceIn(start, lineText.length)
                        if (start > column) {
                            val plain = lineText.substring(column, start)
                            graphics.drawString(font, plain, drawX, drawY, 0xFFE6ECF5.toInt(), false)
                            drawX += font.width(plain)
                        }
                        val colored = lineText.substring(start, end)
                        graphics.drawString(font, colored, drawX, drawY, highlightColor(token.kind), false)
                        drawX += font.width(colored)
                        column = end
                    }
                    if (column < lineText.length) {
                        graphics.drawString(font, lineText.substring(column), drawX, drawY, 0xFFE6ECF5.toInt(), false)
                    }
                }
            }

            // Cursor caret (blinking by tick).
            if (cursorVisibleRow in 0 until visibleLines) {
                val tick = ru.lazyhat.compukterkraft.core.ui.foundation.TickContext.current
                if ((tick / 15) % 2 == 0) {
                    val line = lines.getOrNull(viewModel.cursorLine) ?: ""
                    val col = viewModel.cursorColumn.coerceIn(0, line.length)
                    val caretX = x + gutterWidth + font.width(line.substring(0, col))
                    val caretY = y + cursorVisibleRow * fontHeight
                    graphics.fill(caretX, caretY, caretX + 1, caretY + fontHeight, 0xFFE6ECF5.toInt())
                }
            }

            // Diagnostic markers in the gutter.
            for (diag in viewModel.diagnostics) {
                val range = diag.range ?: continue
                val line = range.start.line
                val visibleRow = line - startLine
                if (visibleRow !in 0 until visibleLines) continue
                val markerY = y + visibleRow * fontHeight
                val color =
                    when (diag.severity) {
                        IdeDiagnosticSeverity.ERROR -> 0xFFE05555.toInt()
                        IdeDiagnosticSeverity.WARNING -> 0xFFE0A96D.toInt()
                        IdeDiagnosticSeverity.INFO -> 0xFF6D9DE0.toInt()
                    }
                graphics.fill(x, markerY, x + 2, markerY + fontHeight, color)
            }
        } finally {
            popClip()
        }
    }

    override fun measureText(text: String): Int = font.width(text)
}
