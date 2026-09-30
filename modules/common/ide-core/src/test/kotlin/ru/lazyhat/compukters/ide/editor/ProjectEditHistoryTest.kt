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

package ru.lazyhat.compukters.ide.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProjectEditHistoryTest {
    @Test
    fun `one undo restores all documents and ordinary typing retains its grouping`() {
        val first = EditorDocument("old")
        val second = EditorDocument("old old")
        val history = ProjectEditHistory()
        assertIs<ProjectEditHistory.Result.Applied>(
            history.apply(
                listOf(
                    ProjectEditHistory.Replacement(first, 0, "new", 3),
                    ProjectEditHistory.Replacement(second, 0, "new new", 0),
                ),
            ),
        )
        first.type("a")
        first.type("b")
        history.undo(first)
        assertEquals("new", first.materialize())
        assertIs<ProjectEditHistory.Result.Applied>(history.undo(first))
        assertEquals("old", first.materialize())
        assertEquals("old old", second.materialize())
        assertIs<ProjectEditHistory.Result.Applied>(history.redo(second))
        assertEquals("new", first.materialize())
        assertEquals("new new", second.materialize())
    }

    @Test
    fun `later edits in another buffer never permit partial undo`() {
        val first = EditorDocument("a")
        val second = EditorDocument("a")
        val history = ProjectEditHistory()
        history.apply(listOf(ProjectEditHistory.Replacement(first, 0, "b", 0), ProjectEditHistory.Replacement(second, 0, "b", 1)))
        second.type("x")
        assertIs<ProjectEditHistory.Result.Rejected>(history.undo(first))
        assertEquals("b", first.materialize())
        assertEquals("bx", second.materialize())
        history.undo(second)
        history.undo(first)
        assertEquals("a", second.materialize())
    }

    @Test
    fun `stale and oversized replacements leave every buffer and history unchanged`() {
        val first = EditorDocument("a")
        val second = EditorDocument("a", EditorLimits(maxCodeUnits = 2))
        val history = ProjectEditHistory()
        assertIs<ProjectEditHistory.Result.Rejected>(
            history.apply(
                listOf(
                    ProjectEditHistory.Replacement(first, 0, "b", 0),
                    ProjectEditHistory.Replacement(second, 0, "big", 0),
                ),
            ),
        )
        assertEquals("a", first.materialize())
        assertEquals(0, first.revision)
        assertIs<ProjectEditHistory.Result.Rejected>(history.apply(listOf(ProjectEditHistory.Replacement(first, 1, "b", 0))))
        assertEquals(EditorEditResult.NoChange, first.undo())
    }
}
