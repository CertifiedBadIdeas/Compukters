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
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeFindSessionTest {
    @Test
    fun `literal matches preserve UTF16 offsets and do not interpret regex syntax`() {
        val document = EditorDocument("😀 a.b A.B a.b")
        val find = IdeFindSession()
        find.open(document)
        find.edit(IdeEditorInput.Type("a.b"))

        assertEquals(listOf(EditorRange(3, 6), EditorRange(11, 14)), find.view(document)!!.matches)
        assertEquals(EditorRange(3, 6), find.navigate(document, false))
        document.setCaret(3, false)
        document.setCaret(6, true)
        assertEquals(0, find.view(document)!!.selectedIndex)
        assertEquals(EditorRange(11, 14), find.navigate(document, true))
        assertEquals(EditorRange(11, 14), find.navigate(document, false))
        assertEquals(0L, document.revision)
        find.close()
        document.close()
    }

    @Test
    fun `navigation wraps in both directions including from the end of file`() {
        val document = EditorDocument("one two one")
        val find = IdeFindSession()
        find.open(document)
        find.edit(IdeEditorInput.Type("one"))
        document.setCaret(8, false)
        document.setCaret(11, true)
        assertEquals(EditorRange(0, 3), find.navigate(document, false))
        document.setCaret(0, false)
        document.setCaret(3, true)
        assertEquals(EditorRange(8, 11), find.navigate(document, true))
        document.setCaret(document.length, false)
        assertEquals(EditorRange(0, 3), find.navigate(document, false))
        assertEquals(EditorRange(8, 11), find.navigate(document, true))
        find.close()
        document.close()
    }

    @Test
    fun `matches refresh after source edits and source identity changes at same revision`() {
        val first = EditorDocument("test test")
        val second = EditorDocument("no match")
        val find = IdeFindSession()
        find.open(first)
        find.edit(IdeEditorInput.Type("test"))
        assertEquals(2, find.view(first)!!.matches.size)
        assertTrue(find.view(second)!!.matches.isEmpty())
        first.selectAll()
        first.type("test")
        assertEquals(listOf(EditorRange(0, 4)), find.view(first)!!.matches)
        assertEquals(EditorRange(0, 4), find.navigate(first, false, includeCurrent = true))
        find.close()
        first.close()
        second.close()
    }

    @Test
    fun `query editing is independent from source undo and supplementary backspace is safe`() {
        val document = EditorDocument("😀 code")
        val find = IdeFindSession()
        document.type("prefix ")
        val revision = document.revision
        find.open(document)
        find.edit(IdeEditorInput.Type("😀"))
        assertEquals("😀", find.view(document)!!.query)
        find.edit(IdeEditorInput.Backspace)
        assertEquals("", find.view(document)!!.query)
        assertTrue(find.view(document)!!.matches.isEmpty())
        assertNull(find.navigate(document, false))
        find.edit(IdeEditorInput.Undo)
        assertEquals("😀", find.view(document)!!.query)
        assertEquals(revision, document.revision)
        document.undo()
        assertEquals("😀 code", document.materialize())
        find.dismiss()
        assertNull(find.view(document))
        find.close()
        document.close()
    }

    @Test
    fun `opening seeds a single line selection and selects query for replacement`() {
        val document = EditorDocument("alpha beta")
        document.setCaret(0, false)
        document.setCaret(5, true)
        val find = IdeFindSession()
        find.open(document)
        assertEquals("alpha", find.view(document)!!.query)
        assertEquals(EditorRange(0, 5), find.view(document)!!.querySelection)
        find.edit(IdeEditorInput.Type("beta"))
        assertEquals("beta", find.view(document)!!.query)
        find.edit(IdeEditorInput.Type("\nignored"))
        assertEquals("beta", find.view(document)!!.query)
        find.focus(false)
        assertTrue(!find.view(document)!!.focused)
        find.close()
        document.close()
    }
}
