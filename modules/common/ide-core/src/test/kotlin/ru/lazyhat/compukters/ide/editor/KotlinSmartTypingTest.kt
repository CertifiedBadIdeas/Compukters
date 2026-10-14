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

import ru.lazyhat.compukters.ide.highlight.IncrementalKotlinHighlighter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinSmartTypingTest {
    @Test
    fun `KDoc subjects and fenced Kotlin tokens retain comment typing behavior`() {
        for (source in listOf("/** @param[value]", "/**\n * ```kotlin\n * val value")) {
            fixture(source) { document, typing ->
                document.setCaret(document.length)
                typing.type("(")
                assertEquals(source + "(", document.materialize())
            }
        }
    }

    @Test
    fun `pairs wrap and tracked closers remain distinct from ordinary source`() {
        fixture("call") { document, typing ->
            assertTrue(document.setCaret(document.length))
            assertIs<EditorEditResult.Applied>(typing.type("("))
            assertEquals("call()", document.materialize())
            assertEquals(5, document.caretOffset)

            assertIs<EditorEditResult.Applied>(typing.type("😀"))
            assertEquals(EditorEditResult.NoChange, typing.type(")"))
            assertEquals("call(😀)", document.materialize())
            assertEquals(document.length, document.caretOffset)
        }

        fixture(")") { document, typing ->
            assertIs<EditorEditResult.Applied>(typing.type(")"))
            assertEquals("))", document.materialize())
        }

        fixture("привет😀") { document, typing ->
            assertTrue(document.setCaret(0))
            assertTrue(document.setCaret(document.length, extendSelection = true))
            assertIs<EditorEditResult.Applied>(typing.type("["))
            assertEquals("[привет😀]", document.materialize())
            assertEquals("привет😀", document.copySelection())
        }
    }

    @Test
    fun `paired backspace and undo are atomic`() {
        fixture("") { document, typing ->
            assertIs<EditorEditResult.Applied>(typing.type("{"))
            assertEquals("{}", document.materialize())
            assertEquals(1, document.undoEntryCount)

            assertIs<EditorEditResult.Applied>(typing.backspace())
            assertEquals("", document.materialize())
            assertEquals(2, document.undoEntryCount)
            assertIs<EditorEditResult.Applied>(document.undo())
            assertEquals("{}", document.materialize())
            assertIs<EditorEditResult.Applied>(document.undo())
            assertEquals("", document.materialize())
        }
    }

    @Test
    fun `pairing is suppressed inside strings and comments`() {
        fixture("val text = \"value\"") { document, typing ->
            assertTrue(document.setCaret(document.materialize().indexOf("value") + 2))
            assertIs<EditorEditResult.Applied>(typing.type("("))
            assertEquals("val text = \"va(lue\"", document.materialize())
        }

        fixture("// note") { document, typing ->
            assertTrue(document.setCaret(document.length))
            assertIs<EditorEditResult.Applied>(typing.type("["))
            assertEquals("// note[", document.materialize())
        }
    }

    @Test
    fun `structural enter preserves CRLF and splits an automatic brace pair`() {
        fixture("fun main() {\r\n    ") { document, typing ->
            assertTrue(document.setCaret(document.length))
            assertIs<EditorEditResult.Applied>(typing.type("{"))
            assertIs<EditorEditResult.Applied>(typing.enter())

            assertEquals("fun main() {\r\n    {\r\n        \r\n    }", document.materialize())
            assertEquals("fun main() {\r\n    {\r\n        ".length, document.caretOffset)
            assertNull(document.selectionRange)
            assertIs<EditorEditResult.Applied>(document.undo())
            assertEquals("fun main() {\r\n    {}", document.materialize())
        }
    }

    @Test
    fun `enter splits existing spaced lambda braces and preserves endings indentation and caret history`() {
        for (separator in listOf("\n", "\r\n")) {
            for (indent in listOf("    ", "\t")) {
                val source = "fun main() {$separator${indent}maven {  }$separator}"
                fixture(source) { document, typing ->
                    val caret = source.indexOf("{  }") + 2
                    document.setCaret(caret)
                    assertIs<EditorEditResult.Applied>(typing.enter())
                    val expected = "fun main() {$separator${indent}maven {$separator$indent    $separator$indent}$separator}"
                    assertEquals(expected, document.materialize())
                    val expectedCaret = expected.indexOf("maven {") + "maven {".length + separator.length + indent.length + 4
                    assertEquals(expectedCaret, document.caretOffset)
                    assertIs<EditorEditResult.Applied>(document.undo())
                    assertEquals(source, document.materialize())
                    assertEquals(caret, document.caretOffset)
                    assertIs<EditorEditResult.Applied>(document.redo())
                    assertEquals(expected, document.materialize())
                    assertEquals(expectedCaret, document.caretOffset)
                }
            }
        }
    }

    @Test
    fun `enter does not split apparent braces in strings comments or nonempty blocks`() {
        for (source in listOf("val value = \"{  }\"", "// {  }", "/* {  } */", "val value = \"\"\"{  }\"\"\"")) {
            fixture(source) { document, typing ->
                val caret = source.indexOf("{  }") + 2
                document.setCaret(caret)
                typing.enter()
                assertEquals(source.substring(0, caret) + "\n" + source.substring(caret), document.materialize())
            }
        }
        fixture("maven { value }") { document, typing ->
            document.setCaret("maven { ".length)
            typing.enter()
            assertEquals("maven { \n    value }", document.materialize())
        }
    }

    @Test
    fun `rejected structural edit retains its automatic pair`() {
        val document = EditorDocument("", EditorLimits(maxCodeUnits = 2, maxUtf8Bytes = 2))
        val highlighter = IncrementalKotlinHighlighter(document)
        KotlinSmartTyping(document, highlighter).use { typing ->
            assertIs<EditorEditResult.Applied>(typing.type("{"))
            assertEquals(EditorEditResult.Rejected(EditorRejection.CodeUnitLimit), typing.enter())
            assertIs<EditorEditResult.Applied>(typing.backspace())
            assertEquals("", document.materialize())
        }
        highlighter.close()
    }

    @Test
    fun `paste rebases common indentation and preserves nested offsets with atomic undo`() {
        for (separator in listOf("\n", "\r\n")) {
            val source = "fun main() {$separator    $separator}"
            fixture(source) { document, typing ->
                val caret = source.indexOf("    ") + 4
                document.setCaret(caret)
                val fragment = "        if (ready) {\n            run()\n        }"
                assertIs<EditorEditResult.Applied>(typing.paste(fragment))
                val expected = "fun main() {$separator    if (ready) {$separator        run()$separator    }$separator}"
                assertEquals(expected, document.materialize())
                assertEquals(expected.lastIndexOf(separator + "}"), document.caretOffset)
                assertEquals(1, document.undoEntryCount)
                document.undo()
                assertEquals(source, document.materialize())
                assertEquals(caret, document.caretOffset)
                document.redo()
                assertEquals(expected, document.materialize())
            }
        }
    }

    @Test
    fun `paste retains relative tab indentation blank lines and replaces backward selection`() {
        fixture("    old") { document, typing ->
            document.setCaret(7)
            document.setCaret(4, true)
            typing.paste("\tfirst\n\t\tsecond\n\n\tthird\n")
            assertEquals("    first\n        second\n\n    third\n    ", document.materialize())
            document.undo()
            assertEquals("    old", document.materialize())
            assertEquals("old", document.copySelection())
            assertEquals(4, document.caretOffset)
        }
        fixture("  ") { document, typing ->
            document.setCaret(2)
            typing.paste("\tfirst\n\t\tsecond")
            assertEquals("  first\n      second", document.materialize())
        }
        fixture("\t") { document, typing ->
            document.setCaret(1)
            typing.paste("  first\n\tsecond")
            assertEquals("\tfirst\n\t  second", document.materialize())
        }
    }

    @Test
    fun `paste in literals comments plain text or fragments containing raw strings remains exact`() {
        for (source in listOf("// note ", "val s = \"text", "val s = \"\"\"text", "/* note ")) {
            fixture(source) { document, typing ->
                document.setCaret(document.length)
                val fragment = "    a\r\n        b"
                typing.paste(fragment)
                assertEquals(source + fragment, document.materialize())
            }
        }
        fixture("    ") { document, typing ->
            document.setCaret(4)
            val fragment = "val raw = \"\"\"\n  literal\n\"\"\"\nnext()"
            typing.paste(fragment)
            assertEquals("    " + fragment, document.materialize())
        }
        fixture("") { document, typing ->
            typing.paste("(")
            assertEquals("(", document.materialize())
        }
    }

    @Test
    fun `rejected indented paste preserves source caret and history`() {
        val document = EditorDocument("    ", EditorLimits(maxCodeUnits = 8))
        val highlighter = IncrementalKotlinHighlighter(document)
        KotlinSmartTyping(document, highlighter).use { typing ->
            document.setCaret(4)
            assertEquals(EditorEditResult.Rejected(EditorRejection.CodeUnitLimit), typing.paste("a\nb"))
            assertEquals("    ", document.materialize())
            assertEquals(4, document.caretOffset)
            assertEquals(0, document.undoEntryCount)
        }
        highlighter.close()
    }

    private fun fixture(
        source: String,
        block: (EditorDocument, KotlinSmartTyping) -> Unit,
    ) {
        val document = EditorDocument(source)
        val highlighter = IncrementalKotlinHighlighter(document)
        KotlinSmartTyping(document, highlighter).use { typing -> block(document, typing) }
        highlighter.close()
    }
}
