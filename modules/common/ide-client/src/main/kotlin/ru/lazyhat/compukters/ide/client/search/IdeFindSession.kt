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

import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeHorizontalDirection
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorLimits
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.util.Collections

data class IdeFindView(
    val query: String,
    val queryCaretUtf16: Int,
    val querySelection: EditorRange?,
    val matches: List<EditorRange>,
    val selectedIndex: Int,
    val focused: Boolean,
)

/** Literal, case-sensitive current-document search; the query has its own editing history. */
class IdeFindSession : AutoCloseable {
    private val query = EditorDocument("", EditorLimits(maxCodeUnits = 4096, maxUtf8Bytes = 16384))
    private var source: EditorDocument? = null
    private var sourceRevision = -1L
    private var matchedQuery = ""
    private var matches: List<EditorRange> = emptyList()
    var visible = false
        private set
    var focused = false
        private set

    fun open(document: EditorDocument) {
        visible = true
        focused = true
        val selected = document.selectionRange?.let { document.materialize().substring(it.startUtf16, it.endUtf16) }
        if (!selected.isNullOrEmpty() && '\n' !in selected && '\r' !in selected && selected.length <= 4096) {
            query.replaceAll(selected, selected.length)
        }
        query.selectAll()
        refresh(document)
    }

    fun focus(value: Boolean) {
        focused = value && visible
    }

    fun dismiss() {
        visible = false
        focused = false
        source = null
        matches = emptyList()
    }

    fun edit(input: IdeEditorInput) {
        when (input) {
            is IdeEditorInput.Type -> {
                if ('\n' !in input.text && '\r' !in input.text) query.type(input.text)
            }

            is IdeEditorInput.SetCaret -> {
                query.setCaret(input.offsetUtf16, input.extendSelection)
            }

            is IdeEditorInput.Move -> {
                when (input.direction) {
                    IdeMoveDirection.Left -> query.moveLeft(input.extendSelection)
                    IdeMoveDirection.Right -> query.moveRight(input.extendSelection)
                    IdeMoveDirection.Home -> query.moveHome(input.extendSelection)
                    IdeMoveDirection.End -> query.moveEnd(input.extendSelection)
                    else -> Unit
                }
            }

            is IdeEditorInput.MoveWord -> {
                when (input.direction) {
                    IdeHorizontalDirection.Left -> query.moveWordLeft(input.extendSelection)
                    IdeHorizontalDirection.Right -> query.moveWordRight(input.extendSelection)
                }
            }

            IdeEditorInput.Backspace -> {
                query.backspace()
            }

            IdeEditorInput.Delete -> {
                query.delete()
            }

            IdeEditorInput.DeleteWordBackward -> {
                query.deleteWordBackward()
            }

            IdeEditorInput.DeleteWordForward -> {
                query.deleteWordForward()
            }

            IdeEditorInput.SelectAll -> {
                query.selectAll()
            }

            IdeEditorInput.Undo -> {
                query.undo()
            }

            IdeEditorInput.Redo -> {
                query.redo()
            }

            else -> {
                Unit
            }
        }
    }

    fun view(document: EditorDocument): IdeFindView? {
        if (!visible) return null
        refresh(document)
        val selection = document.selectionRange
        return IdeFindView(
            matchedQuery,
            query.caretOffset,
            query.selectionRange,
            matches,
            matches.indexOf(selection),
            focused,
        )
    }

    /** Returns a match without modifying source content or undo history. */
    fun navigate(
        document: EditorDocument,
        backwards: Boolean,
        includeCurrent: Boolean = false,
    ): EditorRange? {
        refresh(document)
        if (matches.isEmpty()) return null
        val selected = matches.indexOf(document.selectionRange)
        if (selected >= 0 && !includeCurrent) {
            return matches[Math.floorMod(selected + if (backwards) -1 else 1, matches.size)]
        }
        val anchor = document.selectionRange?.startUtf16 ?: document.caretOffset
        return if (backwards) {
            matches.lastOrNull { it.startUtf16 < anchor || (includeCurrent && it.startUtf16 == anchor) } ?: matches.last()
        } else {
            matches.firstOrNull { it.startUtf16 > anchor || (includeCurrent && it.startUtf16 == anchor) } ?: matches.first()
        }
    }

    private fun refresh(document: EditorDocument) {
        val text = query.materialize()
        if (source === document && sourceRevision == document.revision && matchedQuery == text) return
        source = document
        sourceRevision = document.revision
        matchedQuery = text
        if (text.isEmpty()) {
            matches = emptyList()
            return
        }
        val content = document.materialize()
        val found = mutableListOf<EditorRange>()
        var offset = content.indexOf(text)
        while (offset >= 0) {
            found += EditorRange(offset, offset + text.length)
            offset = content.indexOf(text, offset + text.length)
        }
        matches = Collections.unmodifiableList(found)
    }

    override fun close() {
        dismiss()
        query.close()
    }
}
