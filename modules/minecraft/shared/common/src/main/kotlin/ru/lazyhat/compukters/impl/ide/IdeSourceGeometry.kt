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

import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.math.floor

/** Source offsets are UTF-16; each code point occupies one cell and tabs stop every four cells. */
internal object IdeSourceColumns {
    private const val TAB_WIDTH = 4

    fun columnAt(
        value: String,
        endUtf16: Int = value.length,
        initialColumn: Int = 0,
    ): Int {
        var offset = 0
        var column = initialColumn
        while (offset < endUtf16.coerceIn(0, value.length)) {
            val point = value.codePointAt(offset)
            column += if (point == '\t'.code) TAB_WIDTH - column % TAB_WIDTH else 1
            offset += Character.charCount(point)
        }
        return column
    }

    fun offsetAt(
        value: String,
        requestedColumn: Int,
    ): Int {
        var offset = 0
        var column = 0
        while (offset < value.length && column < requestedColumn) {
            val point = value.codePointAt(offset)
            val next = column + if (point == '\t'.code) TAB_WIDTH - column % TAB_WIDTH else 1
            if (next > requestedColumn) break
            column = next
            offset += Character.charCount(point)
        }
        return offset
    }

    fun glyphs(
        value: String,
        initialColumn: Int = 0,
    ): String =
        buildString(value.length) {
            var offset = 0
            var column = initialColumn
            while (offset < value.length) {
                val point = value.codePointAt(offset)
                if (point == '\t'.code) {
                    val spaces = TAB_WIDTH - column % TAB_WIDTH
                    repeat(spaces) { append(' ') }
                    column += spaces
                } else {
                    appendCodePoint(point)
                    column++
                }
                offset += Character.charCount(point)
            }
        }
}

/** Geometry of one admitted source view; shared by drawing, semantic anchors and pointing. */
internal class IdeSourceGeometry(
    private val editor: IdeEditorView.Text,
    private val geometry: IdeRenderGeometry,
) {
    val gutterDigits =
        editor.totalLines
            .toString()
            .length
            .coerceAtLeast(2)
    val codeLeft = geometry.editor.left + (gutterDigits + 2) * geometry.font.cellWidth
    val codeBounds =
        IdeRect(
            minOf(codeLeft, geometry.editor.right),
            geometry.editor.top,
            maxOf(minOf(codeLeft, geometry.editor.right), geometry.editor.right - IDE_SCROLLBAR_SIZE),
            maxOf(
                geometry.editor.top,
                geometry.editor.bottom - IDE_SCROLLBAR_SIZE,
            ),
        )

    fun clipCode(bounds: IdeRect): IdeRect? {
        val left = maxOf(bounds.left, codeBounds.left)
        val top = maxOf(bounds.top, codeBounds.top)
        val right = minOf(bounds.right, codeBounds.right)
        val bottom = minOf(bounds.bottom, codeBounds.bottom)
        return if (right > left && bottom > top) IdeRect(left, top, right, bottom) else null
    }

    val visibleRows = minOf(geometry.codeRows, editor.visibleLines.size)

    fun xAt(
        line: String,
        localUtf16: Int,
    ): Int = codeLeft + (IdeSourceColumns.columnAt(line, localUtf16) - editor.firstVisibleColumn) * geometry.font.cellWidth

    fun rowTop(visibleIndex: Int): Int = geometry.editor.top + visibleIndex * geometry.font.cellHeight

    fun pointerOffset(
        x: Double,
        y: Double,
        sourceGlyphOnly: Boolean = false,
    ): Int? {
        if (!geometry.editor.containsPoint(x, y)) return null
        if (sourceGlyphOnly && x < codeLeft) return null
        val row = floor((y - geometry.editor.top) / geometry.font.cellHeight).toInt()
        if (row !in 0 until visibleRows) return null
        val line = editor.visibleLines[row]
        val requested = editor.firstVisibleColumn + floor((x - codeLeft) / geometry.font.cellWidth).toInt().coerceAtLeast(0)
        if (sourceGlyphOnly && requested !in editor.firstVisibleColumn until IdeSourceColumns.columnAt(line)) return null
        return editor.visibleLineStartsUtf16[row] + IdeSourceColumns.offsetAt(line, requested)
    }

    fun anchor(range: EditorRange): IdeRect? {
        val row = editor.visibleLineStartsUtf16.indexOfLast { it <= range.startUtf16 }
        if (row !in 0 until visibleRows) return null
        val line = editor.visibleLines[row]
        val local = range.startUtf16 - editor.visibleLineStartsUtf16[row]
        if (local !in 0..line.length) return null
        val left = xAt(line, local)
        val right = maxOf(left + geometry.font.cellWidth, xAt(line, range.endUtf16 - editor.visibleLineStartsUtf16[row]))
        return IdeRect(left, rowTop(row), right, rowTop(row) + geometry.font.cellHeight)
    }

    fun caret(): IdeRect? = anchor(EditorRange(editor.caretUtf16, editor.caretUtf16))
}
