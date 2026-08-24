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

package compukter.system.edit

import kotlin.test.Test
import kotlin.test.assertEquals

class EditProgramTest {
    @Test
    fun `gap movement and deletion preserve supplementary scalars`() {
        val buffer = CharArray(16)
        var state = editEmpty(buffer)
        state = editInsertText(buffer, state, "A😀B")
        state = editMoveLeft(buffer, state)
        state = editBackspace(buffer, state)

        assertEquals("AB", editText(buffer, state))

        state = editDelete(buffer, state)
        assertEquals("A", editText(buffer, state))
    }

    @Test
    fun `tab and newline inherit leading indentation`() {
        val buffer = CharArray(32)
        var state = editEmpty(buffer)
        state = editInsertText(buffer, state, "    value")
        state = editInsertNewline(buffer, state)
        state = editInsertTab(buffer, state)

        assertEquals("    value\n        ", editText(buffer, state))
        assertEquals(1, editCursorLine(buffer, state))
        assertEquals(8, editCursorColumn(buffer, state))
    }

    @Test
    fun `line scans and viewports keep the cursor visible`() {
        val buffer = CharArray(32)
        var state = editEmpty(buffer)
        state = editInsertText(buffer, state, "a\nbc\n12345678")

        assertEquals(2, editCursorLine(buffer, state))
        assertEquals(8, editCursorColumn(buffer, state))
        assertEquals(1, editAdjustRowOffset(2, 0, 2))
        assertEquals(5, editAdjustColumnOffset(8, 0, 4))
    }

    @Test
    fun `compaction produces exact text and full insertion is atomic`() {
        val buffer = CharArray(4)
        var state = editEmpty(buffer)
        state = editInsertText(buffer, state, "A😀B")
        val full = state

        state = editInsertText(buffer, state, "!")
        assertEquals(full, state)
        assertEquals("A😀B", editText(buffer, state))

        state = editMoveLeft(buffer, state)
        val length = editCompact(buffer, state)
        assertEquals(4, length)
        assertEquals("A😀B", buffer.concatToString(0, length))
    }
}
