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
package ru.lazyhat.compukterkraft.core.workbench

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkbenchEditorSupportTest {
    @Test
    fun previousWordBoundarySkipsTrailingSpacesThenWord() {
        val text = "hello world  "
        // Cursor at end → step back over trailing spaces, then over "world".
        assertEquals(6, previousWordBoundary(text, text.length))
    }

    @Test
    fun previousWordBoundaryStopsAtNewline() {
        val text = "abc\ndef"
        // Cursor at start of "def" should land just before the newline.
        assertEquals(3, previousWordBoundary(text, 4))
    }

    @Test
    fun previousWordBoundaryConsumesPunctuationRun() {
        val text = "a + b"
        // Cursor at index 4 (before 'b') → step back to before the punct/space run.
        assertEquals(2, previousWordBoundary(text, 4))
    }

    @Test
    fun nextWordBoundaryAdvancesPastWordAndTrailingSpace() {
        val text = "hello   world"
        assertEquals(8, nextWordBoundary(text, 0))
    }

    @Test
    fun nextWordBoundaryStopsAtNewline() {
        val text = "abc\ndef"
        assertEquals(4, nextWordBoundary(text, 3))
    }

    @Test
    fun computeNewlineIndentPreservesLeadingSpaces() {
        val text = "    val x = 1"
        // Caret at end of line.
        assertEquals("    ", computeNewlineIndent(text, 0, text.length))
    }

    @Test
    fun computeNewlineIndentAddsExtraIndentAfterOpeningBrace() {
        val text = "fun main() {"
        assertEquals("    ", computeNewlineIndent(text, 0, text.length))
    }

    @Test
    fun computeNewlineIndentAddsExtraIndentToExistingIndent() {
        val text = "    if (x) {"
        assertEquals("        ", computeNewlineIndent(text, 0, text.length))
    }

    @Test
    fun computeNewlineIndentEmptyOnBlankLine() {
        val text = "no indent"
        assertEquals("", computeNewlineIndent(text, 0, text.length))
    }
}
