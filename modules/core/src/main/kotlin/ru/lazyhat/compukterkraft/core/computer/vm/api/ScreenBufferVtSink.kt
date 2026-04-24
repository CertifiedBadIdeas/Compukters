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

package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtSink

/**
 * Adapts a [VtSink] (1-based VT coordinates) onto the project's [ScreenBuffer]
 * (0-based, mutation-through-cursor API).
 *
 * This is the Epic 1 compat bridge: programs emit VT-100 byte streams, a
 * [ru.lazyhat.compukterkraft.lang.runtime.vt.VtParser] cracks them open, and
 * this class translates the resulting high-level operations into ScreenBuffer
 * mutations. Behavior must match the pre-refactor [VmTerminalApi] exactly.
 */
class ScreenBufferVtSink(
    private val buffer: ScreenBuffer,
) : VtSink {
    private var savedCursorX: Int = 0
    private var savedCursorY: Int = 0

    override fun printChar(ch: Char) {
        buffer.write(ch.toString())
    }

    override fun moveCursor(
        row: Int?,
        col: Int?,
    ) {
        val targetRow = (row ?: 1).coerceAtLeast(1) - 1
        val targetCol = (col ?: 1).coerceAtLeast(1) - 1
        buffer.setCursor(targetCol, targetRow)
    }

    override fun cursorRelative(
        deltaRows: Int,
        deltaCols: Int,
    ) {
        val newX = (buffer.cursorX + deltaCols).coerceIn(0, buffer.width - 1)
        val newY = (buffer.cursorY + deltaRows).coerceIn(0, buffer.height - 1)
        buffer.setCursor(newX, newY)
    }

    override fun eraseDisplay(mode: Int) {
        // Epic 1 implements mode 2 (full clear) only; modes 0/1 are YAGNI.
        if (mode == 2) buffer.clear()
    }

    override fun eraseLine(mode: Int) {
        // Modes 0 ("to end of line") and 2 ("whole line"): pragmatic common case
        // used by shell readLine redraws. Mode 1 is YAGNI.
        if (mode == 0 || mode == 2) {
            val savedX = buffer.cursorX
            val y = buffer.cursorY
            val fillFrom = if (mode == 2) 0 else savedX
            buffer.setCursor(fillFrom, y)
            buffer.write(" ".repeat(buffer.width - fillFrom))
            buffer.setCursor(savedX, y)
        }
    }

    override fun setForegroundColor(color: Int) {
        buffer.setForegroundColour(color)
    }

    override fun setBackgroundColor(color: Int) {
        buffer.setBackgroundColour(color)
    }

    override fun resetAttributes() {
        buffer.setForegroundColour(ScreenBuffer.DEFAULT_FG)
        buffer.setBackgroundColour(ScreenBuffer.DEFAULT_BG)
    }

    override fun saveCursor() {
        savedCursorX = buffer.cursorX
        savedCursorY = buffer.cursorY
    }

    override fun restoreCursor() {
        buffer.setCursor(savedCursorX, savedCursorY)
    }

    override fun lineFeed() {
        // Match the legacy VmTerminalApi.printLine behavior: move to column 0 of
        // next row, scrolling if on the last line.
        if (buffer.cursorY >= buffer.height - 1) {
            buffer.scroll(1)
            buffer.setCursor(0, buffer.height - 1)
        } else {
            buffer.setCursor(0, buffer.cursorY + 1)
        }
    }

    override fun carriageReturn() {
        buffer.setCursor(0, buffer.cursorY)
    }

    override fun backspace() {
        if (buffer.cursorX > 0) {
            buffer.setCursor(buffer.cursorX - 1, buffer.cursorY)
        }
    }
}
