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

    fun rowText(row: Int): String {
        require(row in 0 until rows) { "row out of bounds: $row" }
        return cells.concatToString(startIndex = row * columns, endIndex = row * columns + columns)
    }

    fun renderFrame(
        displayId: Int,
        pixelWidth: Int,
        pixelHeight: Int,
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
                    glyph = glyph(ch),
                )
            }
        }
        return DisplayFrameDelta(
            displayId = displayId,
            sequence = nextSequence++,
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
        const val GLYPH_WIDTH = 5
        const val GLYPH_HEIGHT = 7
        const val GLYPH_ROW_MASK = 0b11111L

        fun isPrintable(ch: Char): Boolean = ch >= ' ' && ch != 0x7F.toChar()

        fun glyph(ch: Char): Long =
            when (ch) {
                ' ' -> 0b00000000000000000000000000000000000L
                '!' -> 0b00100001000010000100001000000000100L
                '#' -> 0b01010111110101011111010100000000000L
                '\'' -> 0b00100001000000000000000000000000000L
                '-' -> 0b00000000000000011111000000000000000L
                '.' -> 0b00000000000000000000000000000000100L
                '/' -> 0b00001000100001000100010000100010000L
                '0' -> 0b01110100011001110101110011000101110L
                '1' -> 0b00100011000010000100001000010001110L
                '2' -> 0b01110100010000100010001000100011111L
                '3' -> 0b11110000010000100110000010000111110L
                '4' -> 0b00010001100101010010111110001000010L
                '5' -> 0b11111100001111000001000010000111110L
                '6' -> 0b01111100001000011110100011000101110L
                '7' -> 0b11111000010001000100010000100001000L
                '8' -> 0b01110100011000101110100011000101110L
                '9' -> 0b01110100011000101111000010000111110L
                ':' -> 0b00000001000010000000001000010000000L
                '<' -> 0b00001000100010001000001000001000001L
                '>' -> 0b10000010000010000010001000100010000L
                '?' -> 0b01110100010000100010001000000000100L
                'A' -> 0b01110100011000111111100011000110001L
                'B' -> 0b11110100011000111110100011000111110L
                'C' -> 0b01111100001000010000100001000001111L
                'D' -> 0b11110100011000110001100011000111110L
                'E' -> 0b11111100001000011110100001000011111L
                'F' -> 0b11111100001000011110100001000010000L
                'G' -> 0b01111100001000010011100011000101111L
                'H' -> 0b10001100011000111111100011000110001L
                'I' -> 0b11111001000010000100001000010011111L
                'J' -> 0b00111000100001000010100101001001100L
                'K' -> 0b10001100101010011000101001001010001L
                'L' -> 0b10000100001000010000100001000011111L
                'M' -> 0b10001110111010110101100011000110001L
                'N' -> 0b10001110011010110011100011000110001L
                'O' -> 0b01110100011000110001100011000101110L
                'P' -> 0b11110100011000111110100001000010000L
                'Q' -> 0b01110100011000110001101011001001101L
                'R' -> 0b11110100011000111110101001001010001L
                'S' -> 0b01111100001000001110000010000111110L
                'T' -> 0b11111001000010000100001000010000100L
                'U' -> 0b10001100011000110001100011000101110L
                'V' -> 0b10001100011000110001100010101000100L
                'W' -> 0b10001100011000110101101011010101010L
                'X' -> 0b10001010100010000100001000101010001L
                'Y' -> 0b10001010100010000100001000010000100L
                'Z' -> 0b11111000010001000100010001000011111L
                '_' -> 0b00000000000000000000000000000011111L
                '`' -> 0b00100001000000000000000000000000000L
                'a' -> 0b00000000000111000001011111000101111L
                'b' -> 0b10000100001011011001100011000111110L
                'c' -> 0b00000000000111010001100001000101110L
                'd' -> 0b00001000010110110011100011000101111L
                'e' -> 0b00000000000111010001111111000001111L
                'f' -> 0b00110010001110001000010000100001000L
                'g' -> 0b00000011111000110001011110000101110L
                'h' -> 0b10000100001011011001100011000110001L
                'i' -> 0b00100000000010000100001000010000100L
                'j' -> 0b00001000000000100001000011000101110L
                'k' -> 0b10000100001001010100110001010010001L
                'l' -> 0b00100001000010000100001000010000100L
                'm' -> 0b00000000001101010101101011000110001L
                'n' -> 0b00000000001111010001100011000110001L
                'o' -> 0b00000000000111010001100011000101110L
                'p' -> 0b00000111101000110001111101000010000L
                'q' -> 0b00000011111000110001011110000100001L
                'r' -> 0b00000000001011011001100001000010000L
                's' -> 0b00000000000111110000011100000111110L
                't' -> 0b00100001000111000100001000010000010L
                'u' -> 0b00000000001000110001100011000101111L
                'v' -> 0b00000000001000110001100010101000100L
                'w' -> 0b00000000001000110001101011010101010L
                'x' -> 0b00000000001000101010001000101010001L
                'y' -> 0b00000100011000101111000010000101110L
                'z' -> 0b00000000001111100010001000100011111L
                '─' -> 0b00000000000000011111000000000000000L
                '│' -> 0b00100001000010000100001000010000100L
                '┌' -> 0b00000000000011100100001000010000100L
                '┐' -> 0b00000000001110000100001000010000100L
                '└' -> 0b00100001000010000111000000000000000L
                '┘' -> 0b00100001000010011100000000000000000L
                '┼' -> 0b00100001000010011111001000010000100L
                else -> 0b11111100011000110001100011000111111L
            }
    }
}
