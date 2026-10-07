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

package ru.lazyhat.compukters.ide.client.git

import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeHorizontalDirection
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorLimits
import ru.lazyhat.compukters.ide.editor.EditorRange

enum class IdeGitField { Message, AuthorName, AuthorEmail }

data class IdeGitFieldView(
    val text: String = "",
    val caret: Int = 0,
    val selection: EditorRange? = null,
)

data class IdeGitDraftView(
    val message: IdeGitFieldView = IdeGitFieldView(),
    val authorName: IdeGitFieldView = IdeGitFieldView(),
    val authorEmail: IdeGitFieldView = IdeGitFieldView(),
    val focused: IdeGitField? = null,
) {
    val canCommit: Boolean get() = message.text.isNotBlank() && authorName.text.isNotBlank() && authorEmail.text.isNotBlank()

    fun field(field: IdeGitField): IdeGitFieldView =
        when (field) {
            IdeGitField.Message -> message
            IdeGitField.AuthorName -> authorName
            IdeGitField.AuthorEmail -> authorEmail
        }
}

/** Session-local draft with ordinary editor cursor, selection and undo semantics. */
class IdeGitDraftSession : AutoCloseable {
    private val fields = IdeGitField.entries.associateWith(::newDocument).toMutableMap()

    private fun newDocument(field: IdeGitField): EditorDocument {
        val maximum = if (field == IdeGitField.Message) 8192 else 256
        return EditorDocument("", EditorLimits(maxCodeUnits = maximum, maxUtf8Bytes = maximum * 4))
    }

    var focused: IdeGitField? = null
        private set

    fun focus(field: IdeGitField?) {
        focused = field
    }

    fun edit(input: IdeEditorInput) {
        val field = focused ?: return
        val document = fields.getValue(field)
        when (input) {
            is IdeEditorInput.Type -> {
                if (field == IdeGitField.Message ||
                    input.text.none { it == '\r' || it == '\n' }
                ) {
                    document.type(input.text)
                }
            }

            is IdeEditorInput.SetCaret -> {
                document.setCaret(input.offsetUtf16.coerceAtMost(document.length), input.extendSelection)
            }

            is IdeEditorInput.Move -> {
                when (input.direction) {
                    IdeMoveDirection.Left -> document.moveLeft(input.extendSelection)
                    IdeMoveDirection.Right -> document.moveRight(input.extendSelection)
                    IdeMoveDirection.Home -> document.moveHome(input.extendSelection)
                    IdeMoveDirection.End -> document.moveEnd(input.extendSelection)
                    IdeMoveDirection.Up -> document.moveUp(input.extendSelection)
                    IdeMoveDirection.Down -> document.moveDown(input.extendSelection)
                }
            }

            is IdeEditorInput.MoveWord -> {
                when (input.direction) {
                    IdeHorizontalDirection.Left -> document.moveWordLeft(input.extendSelection)
                    IdeHorizontalDirection.Right -> document.moveWordRight(input.extendSelection)
                }
            }

            IdeEditorInput.Backspace -> {
                document.backspace()
            }

            IdeEditorInput.Delete -> {
                document.delete()
            }

            IdeEditorInput.DeleteWordBackward -> {
                document.deleteWordBackward()
            }

            IdeEditorInput.DeleteWordForward -> {
                document.deleteWordForward()
            }

            IdeEditorInput.SelectAll -> {
                document.selectAll()
            }

            IdeEditorInput.Cut -> {
                document.cut()
            }

            IdeEditorInput.Undo -> {
                document.undo()
            }

            IdeEditorInput.Redo -> {
                document.redo()
            }

            IdeEditorInput.Enter -> {
                if (field == IdeGitField.Message) document.type("\n")
            }

            else -> {
                Unit
            }
        }
    }

    fun clearMessage() {
        fields.getValue(IdeGitField.Message).close()
        fields[IdeGitField.Message] = newDocument(IdeGitField.Message)
        focused = null
    }

    fun view(): IdeGitDraftView {
        fun field(field: IdeGitField): IdeGitFieldView {
            val document = fields.getValue(field)
            return IdeGitFieldView(document.materialize(), document.caretOffset, document.selectionRange)
        }
        return IdeGitDraftView(field(IdeGitField.Message), field(IdeGitField.AuthorName), field(IdeGitField.AuthorEmail), focused)
    }

    override fun close() {
        fields.values.forEach(EditorDocument::close)
    }
}
