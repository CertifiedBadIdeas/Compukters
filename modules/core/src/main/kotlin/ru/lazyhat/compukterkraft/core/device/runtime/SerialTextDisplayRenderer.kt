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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.core.gui.GeneratedTerminalFont
import ru.lazyhat.compukterkraft.core.gui.TerminalFontConstants
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile

class SerialTextDisplayRenderer(
    private val columns: Int,
    private val rows: Int,
) {
    private val cells = CharArray(columns * rows) { ' ' }
    private var cursorColumn = 0
    private var cursorRow = 0
    private var nextSequence = 1L

    init {
        require(columns > 0) { "columns must be positive" }
        require(rows > 0) { "rows must be positive" }
    }

    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        for (ch in bytes.toString(Charsets.UTF_8)) {
            append(ch)
        }
    }

    fun replaceCells(bytes: ByteArray) {
        cells.fill(' ')
        val count = minOf(bytes.size, cells.size)
        var index = 0
        while (index < count) {
            val ch = (bytes[index].toInt() and 0xff).toChar()
            cells[index] = if (isPrintable(ch)) ch else ' '
            index += 1
        }
    }

    fun rowText(row: Int): String {
        require(row in 0 until rows) { "row out of bounds: $row" }
        return cells.concatToString(startIndex = row * columns, endIndex = row * columns + columns)
    }

    fun renderFrame(
        displayId: Int,
        pixelWidth: Int,
        pixelHeight: Int,
        sequence: Long = nextSequence++,
    ): DisplayFrameDelta {
        require(pixelWidth > 0) { "pixelWidth must be positive" }
        require(pixelHeight > 0) { "pixelHeight must be positive" }
        val payload = ByteArray(pixelWidth * pixelHeight * RGB565_BYTES)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val ch = cells[row * columns + column]
                drawGlyph(
                    payload = payload,
                    pixelWidth = pixelWidth,
                    pixelHeight = pixelHeight,
                    x = column * TerminalFontConstants.FONT_WIDTH,
                    y = row * TerminalFontConstants.FONT_HEIGHT,
                    glyph = GeneratedTerminalFont.glyph(ch),
                )
            }
        }
        return DisplayFrameDelta(
            displayId = displayId,
            sequence = sequence,
            width = pixelWidth,
            height = pixelHeight,
            pixelFormat = DisplayPixelFormat.RGB565,
            fullRefresh = true,
            tiles =
                listOf(
                    DisplayTile(
                        tileX = 0,
                        tileY = 0,
                        x = 0,
                        y = 0,
                        width = pixelWidth,
                        height = pixelHeight,
                        payload = payload,
                    ),
                ),
        )
    }

    private fun append(ch: Char) {
        when (ch) {
            '\n' -> newline()
            '\r' -> cursorColumn = 0
            '\b' -> backspace()
            else -> {
                if (!isPrintable(ch) || cursorColumn >= columns) return
                cells[cursorRow * columns + cursorColumn] = ch
                cursorColumn += 1
            }
        }
    }

    private fun newline() {
        cursorColumn = 0
        if (cursorRow < rows - 1) {
            cursorRow += 1
        } else {
            scrollUp()
        }
    }

    private fun backspace() {
        if (cursorColumn <= 0) return
        cursorColumn -= 1
        cells[cursorRow * columns + cursorColumn] = ' '
    }

    private fun scrollUp() {
        cells.copyInto(cells, destinationOffset = 0, startIndex = columns)
        cells.fill(' ', fromIndex = (rows - 1) * columns)
    }

    private fun drawGlyph(
        payload: ByteArray,
        pixelWidth: Int,
        pixelHeight: Int,
        x: Int,
        y: Int,
        glyph: Long,
    ) {
        if (glyph == 0L) return
        for (glyphRow in 0 until GLYPH_HEIGHT) {
            val bits = ((glyph shr ((GLYPH_HEIGHT - 1 - glyphRow) * GLYPH_WIDTH)) and GLYPH_ROW_MASK).toInt()
            for (glyphColumn in 0 until GLYPH_WIDTH) {
                if ((bits and (1 shl (GLYPH_WIDTH - 1 - glyphColumn))) == 0) continue
                val pixelX = x + glyphColumn
                val pixelY = y + glyphRow + 1
                if (pixelX !in 0 until pixelWidth || pixelY !in 0 until pixelHeight) continue
                val offset = (pixelY * pixelWidth + pixelX) * RGB565_BYTES
                payload[offset] = (FOREGROUND_RGB565 ushr 8).toByte()
                payload[offset + 1] = FOREGROUND_RGB565.toByte()
            }
        }
    }

    private companion object {
        const val RGB565_BYTES = 2
        const val FOREGROUND_RGB565 = 0xFFFF
        const val GLYPH_WIDTH = GeneratedTerminalFont.GLYPH_WIDTH
        const val GLYPH_HEIGHT = GeneratedTerminalFont.GLYPH_HEIGHT
        const val GLYPH_ROW_MASK = (1L shl GeneratedTerminalFont.GLYPH_WIDTH) - 1L

        fun isPrintable(ch: Char): Boolean = ch >= ' ' && ch != 0x7F.toChar()
    }
}
