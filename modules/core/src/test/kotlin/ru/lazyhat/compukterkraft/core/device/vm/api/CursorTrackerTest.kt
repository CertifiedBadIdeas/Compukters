package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.vt.VtParser
import kotlin.test.Test
import kotlin.test.assertEquals

class CursorTrackerTest {
    private fun feed(vararg chunks: String): CursorTracker {
        val tracker = CursorTracker()
        val parser = VtParser(tracker)
        chunks.forEach(parser::feed)
        return tracker
    }

    @Test
    fun printAdvancesX() {
        val t = feed("Hi")
        assertEquals(2, t.cursorX)
        assertEquals(0, t.cursorY)
    }

    @Test
    fun lineFeedMovesDownCarriageReturnResetsX() {
        val t = feed("Hi\r\n")
        assertEquals(0, t.cursorX)
        assertEquals(1, t.cursorY)
    }

    @Test
    fun bareLineFeedBehavesAsCrLf() {
        // Matches ScreenBufferVtSink: LF alone resets X to 0 and advances Y.
        // Keeps server-side cursor in sync with the client ScreenBuffer so
        // readLine's backspace arithmetic produces correct CSI H coordinates.
        val t = feed("abc\n")
        assertEquals(0, t.cursorX)
        assertEquals(1, t.cursorY)
    }

    @Test
    fun csiHSetsAbsolutePositionZeroBased() {
        val t = feed("\u001B[5;3H")
        assertEquals(2, t.cursorX)
        assertEquals(4, t.cursorY)
    }

    @Test
    fun backspaceDecrementsButFloorsAtZero() {
        val t = feed("AB\b\b\b")
        assertEquals(0, t.cursorX)
    }

    @Test
    fun saveRestore() {
        val t = feed("abc\u001B[s___\u001B[u")
        assertEquals(3, t.cursorX)
        assertEquals(0, t.cursorY)
    }

    @Test
    fun cursorRelativeClampsLeftAndUp() {
        val t = feed("\u001B[10A\u001B[10D")
        assertEquals(0, t.cursorX)
        assertEquals(0, t.cursorY)
    }
}
