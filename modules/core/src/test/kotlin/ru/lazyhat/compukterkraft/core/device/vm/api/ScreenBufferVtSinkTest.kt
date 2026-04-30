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

package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenBufferVtSinkTest {
    private fun buffer(
        w: Int = 10,
        h: Int = 3,
    ): ScreenBuffer = ScreenBuffer(width = w, height = h, colour = true)

    @Test
    fun printCharWritesToBufferAtCursor() {
        val buf = buffer()
        val sink = ScreenBufferVtSink(buf)
        sink.printChar('H')
        sink.printChar('i')
        val snap = buf.forceSnapshot()
        assertEquals('H', snap.charAt(0, 0))
        assertEquals('i', snap.charAt(1, 0))
    }

    @Test
    fun moveCursorConvertsOneBasedToZeroBased() {
        val buf = buffer(w = 10, h = 5)
        val sink = ScreenBufferVtSink(buf)
        sink.moveCursor(row = 2, col = 4)
        sink.printChar('X')
        val snap = buf.forceSnapshot()
        assertEquals('X', snap.charAt(3, 1))
    }

    @Test
    fun moveCursorWithNullDefaultsToFirstRowCol() {
        val buf = buffer()
        val sink = ScreenBufferVtSink(buf)
        buf.setCursor(5, 2)
        sink.moveCursor(row = null, col = null)
        sink.printChar('Q')
        val snap = buf.forceSnapshot()
        assertEquals('Q', snap.charAt(0, 0))
    }

    @Test
    fun eraseDisplayModeTwoClears() {
        val buf = buffer(w = 4, h = 2)
        val sink = ScreenBufferVtSink(buf)
        sink.printChar('A')
        sink.printChar('B')
        sink.eraseDisplay(2)
        val snap = buf.forceSnapshot()
        assertEquals(' ', snap.charAt(0, 0))
        assertEquals(' ', snap.charAt(1, 0))
    }

    @Test
    fun cursorRelativeMovesAndClamps() {
        val buf = buffer(w = 5, h = 5)
        val sink = ScreenBufferVtSink(buf)
        buf.setCursor(2, 2)
        sink.cursorRelative(1, 1)
        sink.printChar('*')
        val snap = buf.forceSnapshot()
        assertEquals('*', snap.charAt(3, 3))
    }

    @Test
    fun saveAndRestoreCursor() {
        val buf = buffer(w = 10, h = 3)
        val sink = ScreenBufferVtSink(buf)
        buf.setCursor(4, 1)
        sink.saveCursor()
        buf.setCursor(0, 0)
        sink.restoreCursor()
        sink.printChar('R')
        val snap = buf.forceSnapshot()
        assertEquals('R', snap.charAt(4, 1))
    }

    @Test
    fun lineFeedMovesToColumnZeroNextRow() {
        val buf = buffer(w = 10, h = 3)
        val sink = ScreenBufferVtSink(buf)
        sink.printChar('A')
        sink.lineFeed()
        sink.printChar('B')
        val snap = buf.forceSnapshot()
        assertEquals('A', snap.charAt(0, 0))
        assertEquals('B', snap.charAt(0, 1))
    }

    @Test
    fun backspaceMovesCursorLeftNoErase() {
        val buf = buffer()
        val sink = ScreenBufferVtSink(buf)
        sink.printChar('A')
        sink.printChar('B')
        sink.backspace()
        sink.printChar('C')
        val snap = buf.forceSnapshot()
        assertEquals('A', snap.charAt(0, 0))
        assertEquals('C', snap.charAt(1, 0))
    }
}
