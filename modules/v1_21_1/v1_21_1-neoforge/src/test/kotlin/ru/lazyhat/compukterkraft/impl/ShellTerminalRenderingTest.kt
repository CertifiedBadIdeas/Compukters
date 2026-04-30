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
package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [ScreenBuffer] write/scroll behaviour (replaces old TerminalHostWriter tests).
 */
class ShellTerminalRenderingTest {
    @Test
    fun appendsTypedCharactersAfterPrompt() {
        val buffer = ScreenBuffer(12, 2, true)

        buffer.write("/ > ")
        buffer.write("abc")

        val line = (0 until 12).map { buffer.forceSnapshot().charAt(it, 0) }.joinToString("")
        assertEquals("/ > abc     ", line)
        assertEquals(7, buffer.cursorX)
        assertEquals(0, buffer.cursorY)
    }

    @Test
    fun preservesPromptWhenBackspaceErasesLastTypedCharacter() {
        val buffer = ScreenBuffer(12, 2, true)

        buffer.write("/ > ")
        buffer.write("ab")

        buffer.setCursor(5, 0)
        buffer.write(" ")
        buffer.setCursor(5, 0)

        val line = (0 until 12).map { buffer.forceSnapshot().charAt(it, 0) }.joinToString("")
        assertEquals("/ > a       ", line)
        assertEquals(5, buffer.cursorX)
        assertEquals(0, buffer.cursorY)
    }

    @Test
    fun movesToNextLineAfterSubmittingInput() {
        val buffer = ScreenBuffer(12, 2, true)

        buffer.write("/ > ")
        buffer.write("42")
        buffer.println("")

        val snap = buffer.forceSnapshot()
        val line0 = (0 until 12).map { snap.charAt(it, 0) }.joinToString("")
        val line1 = (0 until 12).map { snap.charAt(it, 1) }.joinToString("")
        assertEquals("/ > 42      ", line0)
        assertEquals("            ", line1)
        assertEquals(0, buffer.cursorX)
        assertEquals(1, buffer.cursorY)
    }

    @Test
    fun scrollsWhenPrintingPastLastLine() {
        val buffer = ScreenBuffer(12, 2, true)

        buffer.println("top")
        buffer.println("bottom")

        val snap = buffer.forceSnapshot()
        val line0 = (0 until 12).map { snap.charAt(it, 0) }.joinToString("")
        val line1 = (0 until 12).map { snap.charAt(it, 1) }.joinToString("")
        assertEquals("bottom      ", line0)
        assertEquals("            ", line1)
        assertEquals(0, buffer.cursorX)
        assertEquals(1, buffer.cursorY)
    }
}
