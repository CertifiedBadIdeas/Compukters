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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalSnapshot
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IdeSourceGeometryTest {
    @Test
    fun `code points tabs and fragment projection retain source columns`() {
        val line = "a😀\tb"
        assertEquals(5, IdeSourceColumns.columnAt(line))
        assertEquals(2, IdeSourceColumns.columnAt(line, 3))
        assertEquals("a😀  b", IdeSourceColumns.glyphs(line))
        assertEquals("  b", IdeSourceColumns.glyphs(line.substring(3), 2))
        assertEquals(listOf(0, 1, 3, 3, 4, 5), (0..5).map { IdeSourceColumns.offsetAt(line, it) })
    }

    @Test
    fun `scrolled source maps pointers anchors and gutter without pointing outside visible rows`() {
        val geometry = IdeRenderGeometry.compute(960, 540, 180, 120, true, true, IdeCodeFontProfile.DEFAULT)
        val editor =
            IdeEditorView.Text(
                ProjectPath.file("src/main.kt"),
                listOf("a😀\tb", ""),
                listOf(20, 26),
                4,
                2,
                6,
                24,
                null,
                null,
                0,
                0,
                false,
                false,
                KotlinLexicalSnapshot(0, emptyList()),
                IdeAnalysisState.Idle,
            )
        val source = IdeSourceGeometry(editor, geometry)
        val font = geometry.font
        val top = geometry.editor.top + 0.5
        assertEquals(23, source.pointerOffset(source.codeLeft + 0.5, top, true))
        assertEquals(24, source.pointerOffset(source.codeLeft + 2 * font.cellWidth + 0.5, top, true))
        assertEquals(23, source.pointerOffset(geometry.editor.left + 0.5, top))
        assertNull(source.pointerOffset(geometry.editor.left + 0.5, top, true))
        assertNull(source.pointerOffset(source.codeLeft + 3 * font.cellWidth + 0.5, top, true))
        assertEquals(25, source.pointerOffset(source.codeLeft + 3 * font.cellWidth + 0.5, top))
        assertNull(source.pointerOffset(source.codeLeft + 0.5, geometry.editor.top - 0.5))
        assertNull(source.pointerOffset(source.codeLeft + 0.5, geometry.editor.top + 2 * font.cellHeight + 0.5))
        assertNull(source.anchor(EditorRange(19, 20)))
        assertNull(source.anchor(EditorRange(27, 28)))
        val caret = assertNotNull(source.caret())
        assertEquals(source.xAt(editor.visibleLines.first(), 4), caret.left)
        assertEquals(24, source.pointerOffset(caret.left + 0.5, caret.top + 0.5))
    }
}
