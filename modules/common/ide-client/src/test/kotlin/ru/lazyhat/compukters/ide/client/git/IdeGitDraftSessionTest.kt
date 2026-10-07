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
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IdeGitDraftSessionTest {
    @Test
    fun `draft supports selection unicode editing history and multiline message independently of author`() {
        IdeGitDraftSession().use { draft ->
            draft.focus(IdeGitField.Message)
            draft.edit(IdeEditorInput.Type("hello😀"))
            draft.edit(IdeEditorInput.Backspace)
            assertEquals("hello", draft.view().message.text)
            draft.edit(IdeEditorInput.Enter)
            draft.edit(IdeEditorInput.Type("details"))
            draft.edit(IdeEditorInput.Move(IdeMoveDirection.Home, true))
            draft.edit(IdeEditorInput.Type("body"))
            assertEquals("hello\nbody", draft.view().message.text)
            draft.edit(IdeEditorInput.Undo)
            assertEquals("hello\ndetails", draft.view().message.text)
            draft.focus(IdeGitField.AuthorName)
            draft.edit(IdeEditorInput.Type("Player"))
            draft.edit(IdeEditorInput.Type("\nno"))
            assertEquals("Player", draft.view().authorName.text)
            assertFalse(draft.view().canCommit)
            draft.clearMessage()
            assertEquals("", draft.view().message.text)
            assertEquals("Player", draft.view().authorName.text)
            assertEquals(null, draft.view().focused)
            draft.focus(IdeGitField.Message)
            draft.edit(IdeEditorInput.Undo)
            assertEquals("", draft.view().message.text)
        }
    }
}
