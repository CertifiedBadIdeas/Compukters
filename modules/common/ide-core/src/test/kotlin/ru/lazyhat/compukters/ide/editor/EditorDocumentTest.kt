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
    fun `moving blank and identical lines still carries caret and undo state`() {
        for ((source, caret, expected) in listOf(Triple("x\n\nlast", 2, "x\nlast\n"), Triple("same\nsame", 1, "same\nsame"))) {
            val editor = EditorDocument(source)
            editor.setCaret(caret)
            assertIs<EditorEditResult.Applied>(editor.moveLines(true))
            assertEquals(expected, editor.materialize())
            assertEquals(if (caret == 1) 6 else expected.length, editor.caretOffset)
            editor.undo()
            assertEquals(source, editor.materialize())
            assertEquals(caret, editor.caretOffset)
        }
    }

    @Test
    fun `rejected line movement does not bypass undo budget or change selection`() {
        val editor = EditorDocument("a\nbb", EditorLimits(maxUndoCodeUnits = 4))
        editor.setCaret(1)
        assertEquals(EditorEditResult.Rejected(EditorRejection.UndoLimit), editor.moveLines(true))
        assertEquals("a\nbb", editor.materialize())
        assertEquals(1, editor.caretOffset)
        assertEquals(0, editor.undoEntryCount)
    }

    @Test
    fun `move line preserves caret unicode line separators and final newline with atomic undo`() {
        for (source in listOf("a\nbb\n😀c", "a\r\nbb\r\n😀c", "a\r\nbb\n😀c", "a\nbb\n😀c\n")) {
            val editor = EditorDocument(source)
            val caret = editor.lineStartOffset(1) + 1
            editor.setCaret(caret)
            val separators =
                (0 until editor.lineCount).map { index ->
                    val line = editor.line(index)
                    source.substring(line.contentEndUtf16, line.separatorEndUtf16)
                }
            assertIs<EditorEditResult.Applied>(editor.moveLines(true))
            val contents = if (source.endsWith("\n")) listOf("a", "😀c", "bb", "") else listOf("a", "😀c", "bb")
            val expected = contents.mapIndexed { index, content -> content + separators[index] }.joinToString("")
            assertEquals(expected, editor.materialize())
            assertEquals(editor.lineStartOffset(2) + 1, editor.caretOffset)
            assertEquals(1, editor.undoEntryCount)
            editor.undo()
            assertEquals(source, editor.materialize())
            assertEquals(caret, editor.caretOffset)
            editor.redo()
            assertEquals(expected, editor.materialize())
            editor.moveLines(false)
            assertEquals(source, editor.materialize())
            assertEquals(caret, editor.caretOffset)
        }
    }

    @Test
    fun `move selected block preserves selection direction and excludes ending next line`() {
        for (backward in listOf(false, true)) {
            val source = "top\r\naa\nbb\r\nbottom"
            val editor = EditorDocument(source)
            val start = editor.lineStartOffset(1)
            val end = editor.lineStartOffset(3)
            val anchor = if (backward) end else start
            val caret = if (backward) start else end
            editor.setCaret(anchor)
            editor.setCaret(caret, true)
            editor.moveLines(false)
            assertEquals("aa\r\nbb\ntop\r\nbottom", editor.materialize())
            assertEquals(EditorRange(0, editor.lineStartOffset(2)), editor.selectionRange)
            assertEquals(if (backward) 0 else editor.lineStartOffset(2), editor.caretOffset)
            editor.moveLines(true)
            assertEquals(source, editor.materialize())
            assertEquals(EditorRange(start, end), editor.selectionRange)
            assertEquals(caret, editor.caretOffset)
            editor.undo()
            editor.undo()
            assertEquals(source, editor.materialize())
            assertEquals(caret, editor.caretOffset)
        }
    }

    @Test
    fun `partial multiline selection follows moved block at unterminated end of file`() {
        val editor = EditorDocument("top\nhello\nworld\nend")
        editor.setCaret(5)
        editor.setCaret(13, true)
        editor.moveLines(true)
        assertEquals("top\nend\nhello\nworld", editor.materialize())
        assertEquals(EditorRange(9, 17), editor.selectionRange)
        assertEquals("ello\nwor", editor.copySelection())
        editor.undo()
        assertEquals(EditorRange(5, 13), editor.selectionRange)
        assertEquals("top\nhello\nworld\nend", editor.materialize())
    }

    @Test
    fun `line movement at boundaries or after closing preserves document and history`() {
        for (source in listOf("", "only", "a\nb")) {
            val editor = EditorDocument(source)
            assertEquals(EditorEditResult.NoChange, editor.moveLines(false))
            editor.setCaret(source.length)
            assertEquals(EditorEditResult.NoChange, editor.moveLines(true))
            editor.selectAll()
            assertEquals(EditorEditResult.NoChange, editor.moveLines(false))
            assertEquals(EditorEditResult.NoChange, editor.moveLines(true))
            assertEquals(source, editor.materialize())
            assertEquals(0, editor.undoEntryCount)
            editor.close()
            assertEquals(EditorEditResult.Rejected(EditorRejection.Closed), editor.moveLines(true))
        }
    }

    @Test
    fun `duplicate line moves caret to copy and is a single undoable edit with original line endings`() {
        for ((source, caret, result) in listOf(
            Triple("one\ntwo", 1, "one\none\ntwo" to 5),
            Triple("one\r\ntwo", 1, "one\r\none\r\ntwo" to 6),
            Triple("one\r\ntwo", 7, "one\r\ntwo\r\ntwo" to 12),
            Triple("😀", 2, "😀\n😀" to 5),
            Triple("", 0, "\n" to 1),
            Triple("one\n", 4, "one\n\n" to 5),
        )) {
            val (expected, copiedCaret) = result
            val editor = EditorDocument(source)
            editor.setCaret(caret)
            assertIs<EditorEditResult.Applied>(editor.duplicate())
            assertEquals(expected, editor.materialize())
            assertEquals(copiedCaret, editor.caretOffset)
            assertEquals(1, editor.undoEntryCount)
            assertIs<EditorEditResult.Applied>(editor.undo())
            assertEquals(source, editor.materialize())
            assertEquals(caret, editor.caretOffset)
            assertIs<EditorEditResult.Applied>(editor.redo())
            assertEquals(expected, editor.materialize())
            assertEquals(copiedCaret, editor.caretOffset)
        }
    }

    @Test
    fun `duplicate exact selections preserves direction unicode and repeated copying`() {
        for ((source, start, end) in listOf(Triple("prefix 😀 suffix", 7, 9), Triple("ab\r\ncd\r\nef", 1, 6), Triple("a\nb\n", 0, 2))) {
            for (backward in listOf(false, true)) {
                val editor = EditorDocument(source)
                val anchor = if (backward) end else start
                val caret = if (backward) start else end
                editor.setCaret(anchor)
                editor.setCaret(caret, true)
                val copied = source.substring(start, end)
                assertIs<EditorEditResult.Applied>(editor.duplicate())
                val expected = source.substring(0, end) + copied + source.substring(end)
                assertEquals(expected, editor.materialize())
                assertEquals(EditorRange(end, end + copied.length), editor.selectionRange)
                assertEquals(copied, editor.copySelection())
                assertEquals(caret + copied.length, editor.caretOffset)
                editor.duplicate()
                assertEquals(source.substring(0, end) + copied + copied + source.substring(end), editor.materialize())
                editor.undo()
                assertEquals(expected, editor.materialize())
                editor.undo()
                assertEquals(source, editor.materialize())
                assertEquals(caret, editor.caretOffset)
                assertEquals(EditorRange(start, end), editor.selectionRange)
            }
        }
    }

    @Test
    fun `duplicate rejection leaves source caret selection and undo history untouched`() {
        val editor = EditorDocument("abc", EditorLimits(maxCodeUnits = 3))
        editor.setCaret(1)
        assertEquals(EditorEditResult.Rejected(EditorRejection.CodeUnitLimit), editor.duplicate())
        assertEquals("abc", editor.materialize())
        assertEquals(1, editor.caretOffset)
        assertEquals(0, editor.undoEntryCount)
        editor.setCaret(3, true)
        assertEquals(EditorEditResult.Rejected(EditorRejection.CodeUnitLimit), editor.duplicate())
        assertEquals(EditorRange(1, 3), editor.selectionRange)
        assertEquals(0, editor.undoEntryCount)
        editor.close()
        assertEquals(EditorEditResult.Rejected(EditorRejection.Closed), editor.duplicate())
    }

    @Test
    fun `insertion preserves both selection directions across atomic history`() {
        for (backwards in listOf(false, true)) {
            val source = "fun main() { greet(2) }"
            val document = EditorDocument(source)
            val start = source.indexOf("greet")
            val end = start + 5
            document.setCaret(if (backwards) end else start)
            document.setCaret(if (backwards) start else end, true)
            val caret = document.caretOffset
            val before = document.selectionState
            val insertion = "import sample.greet\n\n"
            assertIs<EditorEditResult.Applied>(document.insertPreservingSelection(0, insertion))
            assertEquals("greet", document.copySelection())
            assertEquals(caret + insertion.length, document.caretOffset)
            assertIs<EditorEditResult.Applied>(document.undo())
            assertEquals(source, document.materialize())
            assertEquals(before, document.selectionState)
            assertIs<EditorEditResult.Applied>(document.redo())
            assertEquals(insertion + source, document.materialize())
            assertEquals("greet", document.copySelection())
            assertEquals(caret + insertion.length, document.caretOffset)
        }
        val unicode = EditorDocument("😀")
        assertIs<EditorEditResult.Rejected>(unicode.insertPreservingSelection(1, "x"))
        assertEquals("😀", unicode.materialize())
    }

    @Test
    fun `whole document width follows edits and undo including tabs and surrogate pairs`() {
        val editor = EditorDocument("a\t😀\nshort")
        assertEquals(5, editor.maximumVisualWidth())
        assertTrue(editor.setCaret(editor.length))
        assertEquals(5, editor.maximumVisualWidth())
        editor.selectAll()
        assertIs<EditorEditResult.Applied>(editor.type("x"))
        assertEquals(1, editor.maximumVisualWidth())
        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals(5, editor.maximumVisualWidth())
    }

    @Test
    fun `atomic edit rejects a caret inside a surrogate pair without modifying text`() {
        val editor = EditorDocument("old")
        assertIs<EditorEditResult.Rejected>(editor.replaceRanges(EditorRange(0, 3), "😀", emptyList(), 1))
        assertEquals("old", editor.materialize())
        assertEquals(0, editor.caretOffset)
    }

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
    fun `word navigation and deletion preserve Unicode scalar boundaries`() {
        val editor = EditorDocument("  привет😀 + next")

        assertTrue(editor.moveWordRight())
        assertEquals(2, editor.caretOffset)
        assertTrue(editor.moveWordRight(extendSelection = true))
        assertEquals("привет", editor.copySelection())
        assertTrue(editor.moveWordRight())
        assertEquals(8, editor.caretOffset)
        assertTrue(editor.moveWordRight())
        assertEquals(11, editor.caretOffset)
        assertIs<EditorEditResult.Applied>(editor.deleteWordForward())
        assertEquals("  привет😀 next", editor.materialize())
        assertIs<EditorEditResult.Applied>(editor.deleteWordBackward())
        assertEquals("  приветnext", editor.materialize())
        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals("  привет😀 next", editor.materialize())
        assertTrue(editor.moveWordLeft())
        assertEquals(8, editor.caretOffset)
    }

    @Test
    fun `word navigation left crosses blank lines one boundary at a time`() {
        val editor = EditorDocument("alpha\r\n\r\n    ")
        assertTrue(editor.setCaret(editor.length))

        assertTrue(editor.moveWordLeft())
        assertEquals("alpha\r\n\r\n".length, editor.caretOffset)
        assertTrue(editor.moveWordLeft())
        assertEquals("alpha\r\n".length, editor.caretOffset)
        assertTrue(editor.moveWordLeft())
        assertEquals("alpha".length, editor.caretOffset)
    }

    @Test
    fun `word deletion backward at an empty line removes only its preceding separator`() {
        val source = "alpha\r\n\r\n\r\nomega"
        val editor = EditorDocument(source)
        assertTrue(editor.setCaret("alpha\r\n\r\n".length))

        assertIs<EditorEditResult.Applied>(editor.deleteWordBackward())

        assertEquals("alpha\r\n\r\nomega", editor.materialize())
        assertEquals("alpha\r\n".length, editor.caretOffset)
        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals(source, editor.materialize())
    }

    @Test
    fun `word deletion backward on an autoindented empty line stops at its start`() {
        val editor = EditorDocument("alpha\r\n\r\n    ")
        assertTrue(editor.setCaret(editor.length))

        assertIs<EditorEditResult.Applied>(editor.deleteWordBackward())

        assertEquals("alpha\r\n\r\n", editor.materialize())
        assertEquals(editor.length, editor.caretOffset)
        assertIs<EditorEditResult.Applied>(editor.deleteWordBackward())
        assertEquals("alpha\r\n", editor.materialize())
    }

    @Test
    fun `line comments preserve indentation caret and undo`() {
        val editor = EditorDocument("    val x = 1")
        editor.setCaret(9)
        assertIs<EditorEditResult.Applied>(editor.toggleLineComments())
        assertEquals("    // val x = 1", editor.materialize())
        assertEquals(12, editor.caretOffset)
        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals("    val x = 1", editor.materialize())
        assertEquals(9, editor.caretOffset)
        assertIs<EditorEditResult.Applied>(editor.redo())
        assertIs<EditorEditResult.Applied>(editor.toggleLineComments())
        assertEquals("    val x = 1", editor.materialize())
    }

    @Test
    fun `comment selection preserves CRLF reverse selection and excludes terminal line`() {
        val source = "one\r\n  two\r\nthree"
        val editor = EditorDocument(source)
        editor.setCaret("one\r\n  two\r\n".length)
        editor.setCaret(0, extendSelection = true)
        assertIs<EditorEditResult.Applied>(editor.toggleLineComments())
        assertEquals("// one\r\n  // two\r\nthree", editor.materialize())
        assertTrue(editor.selectionState.anchorUtf16 > editor.selectionState.caretUtf16)
        assertIs<EditorEditResult.Applied>(editor.toggleLineComments())
        assertEquals(source, editor.materialize())
        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals("// one\r\n  // two\r\nthree", editor.materialize())
    }

    @Test
    fun `mixed comments blank lines and TOML prefixes toggle predictably`() {
        val editor = EditorDocument("// old\n\nnew")
        editor.selectAll()
        editor.toggleLineComments()
        assertEquals("// // old\n\n// new", editor.materialize())
        editor.toggleLineComments()
        assertEquals("// old\n\nnew", editor.materialize())
        val toml = EditorDocument("name = \"test\"")
        toml.toggleLineComments("#")
        assertEquals("# name = \"test\"", toml.materialize())
        toml.toggleLineComments("#")
        assertEquals("name = \"test\"", toml.materialize())
        val empty = EditorDocument("")
        empty.toggleLineComments()
        assertEquals("// ", empty.materialize())
        empty.toggleLineComments()
        assertEquals("", empty.materialize())
    }

    @Test
    fun `block indentation preserves selected text direction and is one undo step`() {
        val editor = EditorDocument("one\r\n  two\r\nthree")
        assertTrue(editor.setCaret("one\r\n  two".length))
        assertTrue(editor.setCaret(0, extendSelection = true))

        assertIs<EditorEditResult.Applied>(editor.indent())
        assertEquals("    one\r\n      two\r\nthree", editor.materialize())
        assertTrue(editor.caretOffset < editor.selectionRange!!.endUtf16)
        assertIs<EditorEditResult.Applied>(editor.outdent())
        assertEquals("one\r\n  two\r\nthree", editor.materialize())
        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals("    one\r\n      two\r\nthree", editor.materialize())
    }

    @Test
    fun `block indentation excludes unselected line at terminal line start`() {
        val editor = EditorDocument("one\ntwo\nthree")
        assertTrue(editor.setCaret(0))
        assertTrue(editor.setCaret("one\ntwo\n".length, extendSelection = true))

        assertIs<EditorEditResult.Applied>(editor.indent())

        assertEquals("    one\n    two\nthree", editor.materialize())
    }

    @Test
    fun `token selection admits non ASCII identifiers and one punctuation scalar`() {
        val editor = EditorDocument("val привет = 😀")

        assertTrue(editor.selectToken(6))
        assertEquals("привет", editor.copySelection())
        assertTrue(editor.selectToken(11))
        assertEquals("=", editor.copySelection())
        assertTrue(editor.selectToken(13))
        assertEquals("😀", editor.copySelection())
        assertFalse(editor.selectToken(editor.length))
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
        assertEquals(listOf(0, 5, 9), (0 until editor.lineCount).map(editor::lineStartOffset))
    }

    @Test
    fun `range replacement is one undo entry and rejects non-caret UTF-16 boundaries`() {
        val editor = EditorDocument("pri😀x")

        assertEquals(
            EditorEditResult.Rejected(EditorRejection.InvalidRange),
            editor.replaceRange(EditorRange(0, 4), "println"),
        )
        assertEquals("pri😀x", editor.materialize())

        assertIs<EditorEditResult.Applied>(editor.replaceRange(EditorRange(0, 5), "println"))
        assertEquals("printlnx", editor.materialize())
        assertEquals(7, editor.caretOffset)
        assertEquals(1, editor.undoEntryCount)

        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals("pri😀x", editor.materialize())
        assertEquals(0, editor.undoEntryCount)
    }

    @Test
    fun `whole document replacement preserves the mapped caret as one undoable edit`() {
        val initial = "fun main(){println(1)}"
        val formatted = "fun main() {\n    println(1)\n}\n"
        val editor = EditorDocument(initial)
        editor.setCaret(initial.indexOf("println"))

        assertIs<EditorEditResult.Applied>(editor.replaceAll(formatted, formatted.indexOf("println")))
        assertEquals(formatted, editor.materialize())
        assertEquals(formatted.indexOf("println"), editor.caretOffset)
        assertEquals(1, editor.undoEntryCount)

        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals(initial, editor.materialize())
        assertEquals(initial.indexOf("println"), editor.caretOffset)
        assertIs<EditorEditResult.Applied>(editor.redo())
        assertEquals(formatted.indexOf("println"), editor.caretOffset)
    }

    @Test
    fun `compound replacement applies import and symbol as one undoable edit`() {
        val initial = "package application\n\nfun main() { Rem }"
        val editor = EditorDocument(initial)
        val start = initial.indexOf("Rem")

        assertIs<EditorEditResult.Applied>(
            editor.replaceRanges(
                EditorRange(start, start + 3),
                "RemoteApi",
                listOf(
                    EditorTextEdit(
                        EditorRange("package application".length, "package application".length),
                        "\n\nimport library.RemoteApi",
                    ),
                ),
            ),
        )
        assertEquals("package application\n\nimport library.RemoteApi\n\nfun main() { RemoteApi }", editor.materialize())
        assertEquals(1, editor.revision)
        assertEquals(1, editor.undoEntryCount)

        assertIs<EditorEditResult.Applied>(editor.undo())
        assertEquals(initial, editor.materialize())
        assertIs<EditorEditResult.Applied>(editor.redo())
        assertEquals("package application\n\nimport library.RemoteApi\n\nfun main() { RemoteApi }", editor.materialize())
    }

    @Test
    fun `compound replacement rejects overlapping edits without changing the document`() {
        val editor = EditorDocument("abcdef")

        assertEquals(
            EditorEditResult.Rejected(EditorRejection.InvalidRange),
            editor.replaceRanges(
                EditorRange(2, 4),
                "value",
                listOf(EditorTextEdit(EditorRange(3, 3), "import")),
            ),
        )
        assertEquals("abcdef", editor.materialize())
        assertEquals(0, editor.revision)
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
