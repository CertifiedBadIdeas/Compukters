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

import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TOOLTIP
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TOOLTIP_TEXT

internal object IdeDiagnosticTooltip {
    fun draw(
        output: IdeDrawBuilder,
        geometry: IdeRenderGeometry,
        targets: List<IdeHitTarget>,
    ) {
        val rows =
            targets
                .mapNotNull { it.diagnostic }
                .distinctBy { it.diagnostic }
                .sortedByDescending { it.diagnostic.severity.ordinal }
        if (rows.isEmpty()) return
        val anchor = targets.first().bounds
        val font = geometry.font
        val availableHeight = maxOf(anchor.top - geometry.editor.top, geometry.editor.bottom - anchor.bottom)
        val maximumRows = minOf(20, (availableHeight - 12) / font.cellHeight)
        if (maximumRows <= 0) return
        val columns = minOf(80, ((geometry.editor.width - 12) / font.cellWidth).coerceAtLeast(1))
        val lines =
            rows
                .flatMap { row ->
                    val title = row.diagnostic.severity.name
                    listOf(title to IdeSourceStyles.diagnosticColor(row.diagnostic.severity)) +
                        IdePopupText.wrap(row.diagnostic.message, columns, maximumRows + 1).map { range ->
                            row.diagnostic.message
                                .substring(range.startUtf16, range.endUtf16)
                                .trimEnd() to IdeColors.TEXT
                        }
                }.take(maximumRows + 1)
                .toMutableList()
        if (lines.size > maximumRows) {
            lines.subList(maximumRows, lines.size).clear()
            lines[lines.lastIndex] = "More details in Problems" to IdeColors.MUTED
        }
        val width = maxOf(180, lines.maxOf { it.first.codePointCount(0, it.first.length) } * font.cellWidth + 12)
        val popup = geometry.anchoredPopup(anchor, width, lines.size * font.cellHeight + 12)
        output.panel(IdePanelKind.Tooltip, popup.bounds, IdeColors.PANEL_ALT, Z_TOOLTIP)
        output.scissor(IdeScissorDraw(IdeScissorKind.Tooltip, popup.bounds, Z_TOOLTIP))
        lines.forEachIndexed { index, (text, color) ->
            output.code(
                IdeTextKind.Tooltip,
                text,
                popup.bounds.left + 6,
                popup.bounds.top + 6 + font.glyphDrawOffsetY + index * font.cellHeight,
                color,
                popup.bounds,
                z = Z_TOOLTIP_TEXT,
            )
        }
    }
}
