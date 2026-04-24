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

package ru.lazyhat.compukterkraft.lang.runtime.vt

import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingSink : VtSink {
    val events: MutableList<String> = mutableListOf()

    override fun printChar(ch: Char) {
        events += "print($ch)"
    }

    override fun moveCursor(row: Int?, col: Int?) {
        events += "move($row,$col)"
    }

    override fun cursorRelative(deltaRows: Int, deltaCols: Int) {
        events += "rel($deltaRows,$deltaCols)"
    }

    override fun eraseDisplay(mode: Int) {
        events += "eraseDisp($mode)"
    }

    override fun eraseLine(mode: Int) {
        events += "eraseLine($mode)"
    }

    override fun setForegroundColor(color: Int) {
        events += "fg($color)"
    }

    override fun setBackgroundColor(color: Int) {
        events += "bg($color)"
    }

    override fun resetAttributes() {
        events += "reset"
    }

    override fun saveCursor() {
        events += "save"
    }

    override fun restoreCursor() {
        events += "restore"
    }

    override fun lineFeed() {
        events += "lf"
    }

    override fun carriageReturn() {
        events += "cr"
    }

    override fun backspace() {
        events += "bs"
    }

    override fun setCursorVisible(visible: Boolean) {
        events += "cursorVisible($visible)"
    }
}

class VtParserTest {
    @Test
    fun parsesPrintableCharsAndControlChars() {
        val sink = RecordingSink()
        VtParser(sink).feed("ab\r\n\b")
        assertEquals(listOf("print(a)", "print(b)", "cr", "lf", "bs"), sink.events)
    }

    @Test
    fun parsesCsiCursorPositioning() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001B[H\u001B[3;5H\u001B[12H")
        assertEquals(listOf("move(null,null)", "move(3,5)", "move(12,null)"), sink.events)
    }

    @Test
    fun parsesCsiEraseCommands() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001B[J\u001B[2J\u001B[K\u001B[1K")
        assertEquals(
            listOf("eraseDisp(0)", "eraseDisp(2)", "eraseLine(0)", "eraseLine(1)"),
            sink.events,
        )
    }

    @Test
    fun parsesCsiRelativeCursor() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001B[A\u001B[3B\u001B[2C\u001B[D")
        assertEquals(
            listOf("rel(-1,0)", "rel(3,0)", "rel(0,2)", "rel(0,-1)"),
            sink.events,
        )
    }

    @Test
    fun parsesCsiSaveRestore() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001B[s\u001B[u")
        assertEquals(listOf("save", "restore"), sink.events)
    }

    @Test
    fun parsesSgrColorsAndReset() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001B[31m\u001B[42m\u001B[0m\u001B[91m\u001B[102m")
        assertEquals(
            listOf("fg(1)", "bg(2)", "reset", "fg(9)", "bg(10)"),
            sink.events,
        )
    }

    @Test
    fun preservesStateAcrossChunkBoundary() {
        val sink = RecordingSink()
        val parser = VtParser(sink)
        parser.feed("\u001B[3")
        parser.feed(";5H")
        parser.feed("X")
        assertEquals(listOf("move(3,5)", "print(X)"), sink.events)
    }

    @Test
    fun `DECTCEM show cursor emits setCursorVisible(true)`() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001B[?25h")
        assertEquals(listOf("cursorVisible(true)"), sink.events)
    }

    @Test
    fun `DECTCEM hide cursor emits setCursorVisible(false)`() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001B[?25l")
        assertEquals(listOf("cursorVisible(false)"), sink.events)
    }

    @Test
    fun `DECTCEM private mode with unknown param is ignored`() {
        val sink = RecordingSink()
        VtParser(sink).feed("\u001B[?7h")
        assertEquals(emptyList(), sink.events)
    }

    @Test
    fun `DECTCEM does not leak into subsequent non-private CSI`() {
        val sink = RecordingSink()
        val parser = VtParser(sink)
        parser.feed("\u001B[?25h")
        parser.feed("\u001B[2;3H")
        assertEquals(listOf("cursorVisible(true)", "move(2,3)"), sink.events)
    }
}
