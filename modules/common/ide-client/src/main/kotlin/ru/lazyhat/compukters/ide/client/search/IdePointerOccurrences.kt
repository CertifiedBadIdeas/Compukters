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

/** Cached whole-word textual occurrences of the identifier under the source pointer. */
class IdePointerOccurrences {
    private var source: EditorDocument? = null
    private var revision = -1L
    private var content = ""
    private var token: EditorRange? = null
    private var occurrences: List<EditorRange> = emptyList()

    fun pointAt(
        document: EditorDocument?,
        offsetUtf16: Int?,
        lexical: KotlinLexicalSnapshot?,
    ) {
        if (document == null || offsetUtf16 == null || lexical == null) {
            clear()
            return
        }
        if (source !== document || revision != document.revision) {
            source = document
            revision = document.revision
            content = document.materialize()
            token = null
            occurrences = emptyList()
        }
        val range =
            lexicalTokenAt(document, lexical, offsetUtf16)
                ?.takeIf {
                    it.second == KotlinLexicalKind.Identifier || it.second == KotlinLexicalKind.TypeLike
                }?.first
        if (range == token) return
        token = range
        occurrences =
            if (range == null) {
                emptyList()
            } else {
                val text = content.substring(range.startUtf16, range.endUtf16)
                Collections.unmodifiableList(
                    literalMatches(content, text).filter {
                        val candidate = lexicalTokenAt(document, lexical, it.startUtf16)
                        candidate?.first == it &&
                            (candidate.second == KotlinLexicalKind.Identifier || candidate.second == KotlinLexicalKind.TypeLike)
                    },
                )
            }
    }

    fun matches(document: EditorDocument): List<EditorRange> {
        if (source !== document || revision != document.revision) clear()
        return occurrences
    }

    fun clear() {
        source = null
        revision = -1L
        content = ""
        token = null
        occurrences = emptyList()
    }
}
