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

package ru.lazyhat.compukters.ide.client.state

import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.Collections

sealed interface IdeEditorView {
    data object Empty : IdeEditorView

    class Text(
        val path: ProjectPath,
        visibleLines: List<String>,
        val firstVisibleLine: Int,
        val totalLines: Int,
        val caretUtf16: Int,
        val selectionStartUtf16: Int?,
        val selectionEndUtf16: Int?,
        val contentRevision: Long,
        val persistedContentRevision: Long,
        val dirty: Boolean,
        val conflict: Boolean,
    ) : IdeEditorView {
        val visibleLines: List<String> = Collections.unmodifiableList(visibleLines.toList())

        init {
            require(firstVisibleLine >= 0) { "first visible line must be non-negative" }
            require(totalLines > 0) { "text editor must contain at least one line" }
            require(caretUtf16 >= 0) { "caret must be non-negative" }
            require(contentRevision >= 0) { "content revision must be non-negative" }
            require(persistedContentRevision in 0..contentRevision) { "persisted revision must name admitted content" }
        }
    }

    data class Binary(
        val path: ProjectPath,
        val bytes: Long,
    ) : IdeEditorView {
        init {
            require(bytes >= 0) { "binary size must be non-negative" }
        }
    }
}
