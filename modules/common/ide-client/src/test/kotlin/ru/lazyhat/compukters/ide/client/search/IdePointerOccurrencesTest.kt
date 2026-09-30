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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IdePointerOccurrencesTest {
    @Test
    fun `interpolated identifiers are code while adjacent string text is not`() {
        val source = "val maven = maven; \"\$maven \${maven} maven\""
        val document = EditorDocument(source)
        val occurrences = literalMatches(source, "maven")
        IncrementalKotlinHighlighter(document).use { highlighter ->
            val hover = IdePointerOccurrences()
            for (interpolated in occurrences.slice(2..3)) {
                hover.pointAt(document, interpolated.startUtf16 + 1, highlighter.snapshot())
                assertEquals(occurrences.take(4), hover.matches(document))
            }
            hover.pointAt(document, occurrences.last().startUtf16, highlighter.snapshot())
            assertTrue(hover.matches(document).isEmpty())
        }
        document.close()
    }

    @Test
    fun `maven hover skips string literals but selecting maven inside a string matches code too`() {
        val source = "repositories { maven(\"https://maven.example/maven\"); maven(\"https://other.example\") }"
        val document = EditorDocument(source)
        IncrementalKotlinHighlighter(document).use { highlighter ->
            val occurrences = literalMatches(source, "maven")
            val hover = IdePointerOccurrences()
            hover.pointAt(document, occurrences.first().startUtf16, highlighter.snapshot())
            assertEquals(listOf(occurrences.first(), occurrences.last()), hover.matches(document))
            hover.pointAt(document, occurrences[1].startUtf16, highlighter.snapshot())
            assertTrue(hover.matches(document).isEmpty())
            document.setCaret(occurrences[1].startUtf16, false)
            document.setCaret(occurrences[1].endUtf16, true)
            assertEquals(
                occurrences.filterIndexed {
                    index,
                    _,
                    ->
                    index != 1
                },
                IdeSelectionOccurrences().matches(document, highlighter.snapshot()),
            )
        }
        document.close()
    }

    @Test
    fun `hover highlights the word itself and only whole word case sensitive matches`() {
        val document = EditorDocument("call call callback Call")
        val highlights = IdePointerOccurrences()
        highlights.pointAt(document, 1)
        val matches = highlights.matches(document)
        assertEquals(listOf(EditorRange(0, 4), EditorRange(5, 9)), matches)
        highlights.pointAt(document, 2)
        assertSame(matches, highlights.matches(document))
        assertEquals(0L, document.revision)
        assertEquals(0, document.caretOffset)
        document.close()
    }

    @Test
    fun `whitespace punctuation numbers and leaving the editor clear hover`() {
        val document = EditorDocument("word + 123")
        val highlights = IdePointerOccurrences()
        for (offset in listOf(4, 5, 7, 99)) {
            highlights.pointAt(document, offset)
            assertTrue(highlights.matches(document).isEmpty())
        }
        highlights.pointAt(document, 0)
        assertEquals(1, highlights.matches(document).size)
        highlights.pointAt(document, null)
        assertTrue(highlights.matches(document).isEmpty())
        document.close()
    }

    @Test
    fun `Unicode and escaped identifiers retain UTF16 ranges`() {
        val document = EditorDocument("😀 имя имя `two words` `two words`")
        val highlights = IdePointerOccurrences()
        highlights.pointAt(document, 4)
        assertEquals(listOf(EditorRange(3, 6), EditorRange(7, 10)), highlights.matches(document))
        highlights.pointAt(document, 14)
        assertEquals(listOf(EditorRange(11, 22), EditorRange(23, 34)), highlights.matches(document))
        document.close()
    }

    @Test
    fun `document changes and edits invalidate stale pointer highlights`() {
        val document = EditorDocument("one one")
        val other = EditorDocument("two two")
        val highlights = IdePointerOccurrences()
        highlights.pointAt(document, 0)
        assertEquals(2, highlights.matches(document).size)
        assertTrue(highlights.matches(other).isEmpty())
        highlights.pointAt(other, 0)
        assertEquals(2, highlights.matches(other).size)
        other.selectAll()
        other.type("changed")
        assertTrue(highlights.matches(other).isEmpty())
        highlights.pointAt(other, 0)
        assertEquals(listOf(EditorRange(0, 7)), highlights.matches(other))
        highlights.clear()
        assertTrue(highlights.matches(other).isEmpty())
        document.close()
        other.close()
    }
}

private fun IdePointerOccurrences.pointAt(
    document: EditorDocument,
    offset: Int?,
) {
    IncrementalKotlinHighlighter(document).use { pointAt(document, offset, it.snapshot()) }
}
