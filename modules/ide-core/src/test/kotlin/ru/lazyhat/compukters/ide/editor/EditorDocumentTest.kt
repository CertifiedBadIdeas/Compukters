/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.ide.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorDocumentTest {
    @Test
    fun `horizontal navigation and deletion preserve scalar and CRLF boundaries`() {
        val editor = EditorDocument("a😀\r\nb")
        assertTrue(editor.setCaret(3))
        assertTrue(editor.moveLeft())
        assertEquals(1, editor.caretOffset)
        assertTrue(editor.moveRight())
        assertEquals(3, editor.caretOffset)
        assertTrue(editor.moveRight())
        assertEquals(5, editor.caretOffset)
        assertFalse(editor.setCaret(2))
        assertFalse(editor.setCaret(4))

        assertIs<EditorEditResult.Applied>(editor.backspace())
        assertEquals("a😀b", editor.materialize())
        assertEquals(3, editor.caretOffset)
        assertIs<EditorEditResult.Applied>(editor.backspace())
        assertEquals("ab", editor.materialize())
        assertEquals(1, editor.caretOffset)
    }

    @Test
    fun `selection replacement copy cut and shift navigation are exact`() {
        val editor = EditorDocument("one 😀 three")
        assertTrue(editor.setCaret(4))
        assertTrue(editor.moveRight(extendSelection = true))
        assertEquals(EditorRange(4, 6), editor.selectionRange)
        assertEquals("😀", editor.copySelection())
        assertIs<EditorEditResult.Applied>(editor.type("two"))
        assertEquals("one two three", editor.materialize())
        assertNull(editor.selectionRange)

        editor.selectAll()
        assertEquals("one two three", editor.copySelection())
        assertIs<EditorEditResult.Applied>(editor.cut())
        assertEquals("", editor.materialize())
        assertIs<EditorEditResult.Applied>(editor.paste("restored"))
        assertEquals("restored", editor.materialize())
    }

    @Test
    fun `line navigation indentation and tabs use visual columns without normalizing endings`() {
        val editor = EditorDocument("\talpha\r\n    beta\r\n")
        assertEquals("\r\n", editor.preferredLineSeparator)
        assertEquals(3, editor.lineCount)
        assertTrue(editor.setCaret(2))
        assertEquals(5, editor.caretVisualColumn)
        assertTrue(editor.moveDown())
        assertEquals(13, editor.caretOffset)
        assertEquals(5, editor.caretVisualColumn)
        assertTrue(editor.moveHome())
        assertEquals(8, editor.caretOffset)
        assertTrue(editor.moveEnd())
        assertEquals(16, editor.caretOffset)

        assertTrue(editor.setCaret(13))
        assertIs<EditorEditResult.Applied>(editor.enter())
        assertEquals("\talpha\r\n    b\r\n    eta\r\n", editor.materialize())
        assertIs<EditorEditResult.Applied>(editor.tab())
        assertEquals("\talpha\r\n    b\r\n        eta\r\n", editor.materialize())
    }

    @Test
    fun `accepted mutations notify once while navigation rejection and close do not mutate`() {
        val editor = EditorDocument("abc", limits(maxCodeUnits = 4, maxUtf8Bytes = 4))
        val changes = mutableListOf<EditorChange>()
        val subscription = editor.addChangeListener(changes::add)

        assertTrue(editor.setCaret(3))
        assertIs<EditorEditResult.Applied>(editor.type("d"))
        assertEquals(1L, editor.revision)
        assertEquals(EditorChangeOrigin.User, changes.single().origin)
        assertEquals(EditorRange(3, 3), changes.single().oldRange)
        assertEquals(1, changes.single().insertedCodeUnits)

        editor.moveLeft()
        assertEquals(1, changes.size)
        assertEquals(
            EditorEditResult.Rejected(EditorRejection.CodeUnitLimit),
            editor.type("x"),
        )
        assertEquals("abcd", editor.materialize())
        assertEquals(1L, editor.revision)
        assertEquals(1, changes.size)

        editor.close()
        assertEquals(EditorEditResult.Rejected(EditorRejection.Closed), editor.delete())
        subscription.close()
    }

    @Test
    fun `external reset clears selection and history and remains observable`() {
        val editor = EditorDocument("old")
        val changes = mutableListOf<EditorChange>()
        editor.addChangeListener(changes::add)
        editor.selectAll()
        editor.type("dirty")

        editor.reset("fresh")

        assertEquals("fresh", editor.materialize())
        assertNull(editor.selectionRange)
        assertEquals(0, editor.caretOffset)
        assertEquals(EditorEditResult.NoChange, editor.undo())
        assertEquals(EditorChangeOrigin.ExternalReset, changes.last().origin)
    }

    @Test
    fun `editing a later line retains the untouched line index prefix`() {
        val editor = EditorDocument("one\ntwo\nthree")
        val firstLine = editor.line(0)
        val secondLine = editor.line(1)

        assertTrue(editor.setCaret(10))
        assertIs<EditorEditResult.Applied>(editor.type("x"))

        assertSame(firstLine, editor.line(0))
        assertSame(secondLine, editor.line(1))
        assertEquals("one\ntwo\nthxree", editor.materialize())
    }

    @Test
    fun `visible lines can be copied without their separators`() {
        val editor = EditorDocument("one\r\ntwo\n")

        assertEquals(3, editor.lineCount)
        assertEquals("one", editor.materializeLine(0))
        assertEquals("two", editor.materializeLine(1))
        assertEquals("", editor.materializeLine(2))
    }

    private fun limits(
        maxCodeUnits: Int = 128,
        maxUtf8Bytes: Int = 256,
        maxUndoEntries: Int = 16,
        maxUndoCodeUnits: Int = 128,
    ) = EditorLimits(
        maxCodeUnits = maxCodeUnits,
        maxUtf8Bytes = maxUtf8Bytes,
        initialGapCodeUnits = 2,
        maxUndoEntries = maxUndoEntries,
        maxUndoCodeUnits = maxUndoCodeUnits,
        tabWidth = 4,
    )
}
