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

sealed interface IdeEditorInput {
    data class Type(
        val text: String,
    ) : IdeEditorInput

    data class SetCaret(
        val offsetUtf16: Int,
        val extendSelection: Boolean,
    ) : IdeEditorInput {
        init {
            require(offsetUtf16 >= 0) { "caret offset must be non-negative" }
        }
    }

    data class Move(
        val direction: IdeMoveDirection,
        val extendSelection: Boolean,
    ) : IdeEditorInput

    data object Backspace : IdeEditorInput

    data object Delete : IdeEditorInput

    data object Enter : IdeEditorInput

    data object Tab : IdeEditorInput

    data object Undo : IdeEditorInput

    data object Redo : IdeEditorInput

    data object SelectAll : IdeEditorInput
}

enum class IdeMoveDirection {
    Left,
    Right,
    Up,
    Down,
    Home,
    End,
}
