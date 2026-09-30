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
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalSnapshot
import java.util.Collections

/** Literal selection matches use a shorter threshold inside string literal text. */
class IdeSelectionOccurrences {
    private var source: EditorDocument? = null
    private var revision = -1L
    private var selection: EditorRange? = null
    private var occurrences: List<EditorRange> = emptyList()

    fun matches(
        document: EditorDocument,
        lexical: KotlinLexicalSnapshot,
    ): List<EditorRange> {
        val range = document.selectionRange
        if (source === document && revision == document.revision && selection == range) return occurrences
        source = document
        revision = document.revision
        selection = range
        occurrences = emptyList()
        if (range == null) return occurrences
        val token = lexicalTokenAt(document, lexical, range.startUtf16)
        val insideString =
            token != null && range.endUtf16 <= token.first.endUtf16 &&
                (token.second == KotlinLexicalKind.String || token.second == KotlinLexicalKind.MultilineString)
        val content = document.materialize()
        val text = content.substring(range.startUtf16, range.endUtf16)
        if (text.codePointCount(0, text.length) < (if (insideString) 2 else 3) || text.isBlank()) return occurrences
        occurrences = Collections.unmodifiableList(literalMatches(content, text).filterNot { it == range })
        return occurrences
    }

    fun clear() {
        source = null
        revision = -1L
        selection = null
        occurrences = emptyList()
    }
}

internal fun lexicalTokenAt(
    document: EditorDocument,
    lexical: KotlinLexicalSnapshot,
    offsetUtf16: Int,
): Pair<EditorRange, KotlinLexicalKind>? {
    if (offsetUtf16 !in 0 until document.length) return null
    var first = 0
    var end = document.lineCount
    while (first + 1 < end) {
        val middle = (first + end) / 2
        if (document.lineStartOffset(middle) <= offsetUtf16) first = middle else end = middle
    }
    val start = document.lineStartOffset(first)
    val span =
        lexical.lines
            .getOrNull(first)
            ?.spans
            ?.firstOrNull { offsetUtf16 - start in it.startUtf16 until it.endUtf16 } ?: return null
    return EditorRange(start + span.startUtf16, start + span.endUtf16) to span.kind
}
