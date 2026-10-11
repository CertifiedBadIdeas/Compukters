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

import ru.lazyhat.compukters.ide.client.search.IdeFindView
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_CARET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_CONTENT
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_SELECTION

internal class IdeFindRenderer(
    private val output: IdeDrawBuilder,
    private val geometry: IdeRenderGeometry,
) {
    private val font = geometry.font

    fun draw(find: IdeFindView) {
        val bounds = geometry.findBar ?: return
        output.panel(IdePanelKind.Control, bounds, IdeColors.PANEL_ALT)
        val field = IdeRect(bounds.left + 4, bounds.top + 3, bounds.right - 166, bounds.bottom - 3)
        output.fill(IdeFillDraw(IdeFillKind.Border, field, if (find.focused) IdeColors.ACCENT else IdeColors.BORDER, Z_CONTENT + 1))
        val inside = IdeRect(field.left + 1, field.top + 1, field.right - 1, field.bottom - 1)
        output.fill(IdeFillDraw(IdeFillKind.Background, inside, IdeColors.EDITOR, Z_CONTENT + 2))
        output.target(IdeHitAction.FindFocus, field, true, "Find in current file (Ctrl+F)")
        val columns = ((inside.width - 6) / font.cellWidth).coerceAtLeast(1)
        val beforeCaret = visualColumns(find.query.substring(0, find.queryCaretUtf16))
        val scroll = (beforeCaret - columns + 1).coerceAtLeast(0)
        val queryGlyphs = projectGlyphs(find.query)
        val queryStart = queryGlyphs.offsetByCodePoints(0, minOf(scroll, queryGlyphs.codePointCount(0, queryGlyphs.length)))
        val visibleQuery = queryGlyphs.substring(queryStart)
        val x = inside.left + 3
        val y = font.centeredCellTop(inside) + font.glyphDrawOffsetY
        if (find.focused) {
            find.querySelection?.let { selection ->
                val start = visualColumns(find.query.substring(0, selection.startUtf16)) - scroll
                val end = visualColumns(find.query.substring(0, selection.endUtf16)) - scroll
                val left = (x + start * font.cellWidth).coerceIn(inside.left, inside.right)
                val right = (x + end * font.cellWidth).coerceIn(left, inside.right)
                output.fill(
                    IdeFillDraw(
                        IdeFillKind.Selection,
                        IdeRect(left, inside.top, right, inside.bottom),
                        IdeColors.SELECTION,
                        Z_SELECTION,
                    ),
                )
            }
            val caretX = x + (beforeCaret - scroll) * font.cellWidth
            if (caretX in inside.left until inside.right) {
                output.fill(
                    IdeFillDraw(IdeFillKind.Caret, IdeRect(caretX, inside.top, caretX + 1, inside.bottom), IdeColors.CARET, Z_CARET),
                )
            }
        }
        output.code(IdeTextKind.Find, visibleQuery.ifEmpty { if (find.focused) "" else "Find" }, x, y, IdeColors.TEXT, inside)
        val count = "${find.selectedIndex + 1}/${find.matches.size}"
        val counter = IdeRect(bounds.right - 160, bounds.top, bounds.right - 76, bounds.bottom)
        output.ui(
            IdeTextKind.Find,
            count,
            counter.left,
            font.centeredCellTop(counter),
            if (find.query.isNotEmpty() &&
                find.matches.isEmpty()
            ) {
                IdeColors.ERROR
            } else {
                IdeColors.MUTED
            },
            clip = counter,
        )
        val previous = IdeRect(bounds.right - 72, bounds.top + 3, bounds.right - 50, bounds.bottom - 3)
        val next = IdeRect(bounds.right - 48, previous.top, bounds.right - 26, previous.bottom)
        val close = IdeRect(bounds.right - 24, previous.top, bounds.right - 2, previous.bottom)
        output.target(IdeHitAction.FindPrevious, previous, find.matches.isNotEmpty(), "Previous match (Shift+Enter)")
        output.target(IdeHitAction.FindNext, next, find.matches.isNotEmpty(), "Next match (Enter)")
        output.target(IdeHitAction.FindClose, close, true, "Close search (Escape)")
        output.ui(IdeTextKind.Find, "↑", previous.left + 7, font.centeredCellTop(previous))
        output.ui(IdeTextKind.Find, "↓", next.left + 7, font.centeredCellTop(next))
        output.ui(IdeTextKind.Find, "×", close.left + 7, font.centeredCellTop(close))
    }

    private fun visualColumns(value: String): Int = IdeSourceColumns.columnAt(value)

    private fun projectGlyphs(value: String): String = IdeSourceColumns.glyphs(value)
}
