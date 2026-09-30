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

package ru.lazyhat.compukters.ide.client.search

import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.IncrementalKotlinHighlighter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeSelectionOccurrencesTest {
    @Test
    fun `string selections require two Unicode characters and exclude selected occurrence`() {
        val document = EditorDocument("val s = \"ab ab\"; val other = 1")
        IncrementalKotlinHighlighter(document).use { highlighter ->
            val occurrences = IdeSelectionOccurrences()
            document.setCaret(9, false)
            document.setCaret(10, true)
            assertTrue(occurrences.matches(document, highlighter.snapshot()).isEmpty())
            document.setCaret(11, true)
            assertEquals(listOf(EditorRange(12, 14)), occurrences.matches(document, highlighter.snapshot()))
            document.setCaret(0, false)
            document.setCaret(3, true)
            assertEquals(listOf(EditorRange(17, 20)), occurrences.matches(document, highlighter.snapshot()))
            document.setCaret(2, true)
            assertTrue(occurrences.matches(document, highlighter.snapshot()).isEmpty())
        }
        document.close()
    }

    @Test
    fun `one supplementary character is not counted as two letters`() {
        val document = EditorDocument("\"😀 😀\"")
        IncrementalKotlinHighlighter(document).use { highlighter ->
            document.setCaret(1, false)
            document.setCaret(3, true)
            assertTrue(IdeSelectionOccurrences().matches(document, highlighter.snapshot()).isEmpty())
        }
        document.close()
    }

    @Test
    fun `raw string and interpolation selections match literally with their respective thresholds`() {
        val document = EditorDocument("\"\"\"abc abc\"\"\"; \"\$name \$name\"")
        IncrementalKotlinHighlighter(document).use { highlighter ->
            val occurrences = IdeSelectionOccurrences()
            document.setCaret(3, false)
            document.setCaret(6, true)
            assertEquals(listOf(EditorRange(7, 10)), occurrences.matches(document, highlighter.snapshot()))
            val offset = document.materialize().indexOf("name")
            document.setCaret(offset, false)
            document.setCaret(offset + 4, true)
            assertEquals(listOf(EditorRange(offset + 6, offset + 10)), occurrences.matches(document, highlighter.snapshot()))
        }
        document.close()
    }

    @Test
    fun `selection in code matches comments strings and other identifiers without semantic filtering`() {
        val text = "val maven = 1; fun maven() = Unit; // maven\n\"maven\""
        val document = EditorDocument(text)
        IncrementalKotlinHighlighter(document).use { highlighter ->
            val range = literalMatches(text, "maven").first()
            document.setCaret(range.startUtf16, false)
            document.setCaret(range.endUtf16, true)
            assertEquals(literalMatches(text, "maven").drop(1), IdeSelectionOccurrences().matches(document, highlighter.snapshot()))
        }
        document.close()
    }
}
