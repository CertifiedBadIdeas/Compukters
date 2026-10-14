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

import ru.lazyhat.compukters.ide.client.state.IdeDiagnosticRow
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TARGET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TEXT

internal data class IdeDiagnosticSpan(
    val row: IdeDiagnosticRow,
    val range: EditorRange,
)

internal class IdeDiagnosticRenderer(
    private val output: IdeDrawBuilder,
    private val geometry: IdeRenderGeometry,
    private val source: IdeSourceGeometry,
) {
    fun spans(
        editor: IdeEditorView.Text,
        rows: List<IdeDiagnosticRow>,
        line: String,
        lineStart: Int,
        completionRange: EditorRange?,
    ): List<IdeDiagnosticSpan> =
        rows.mapNotNull { row ->
            if (!row.navigable || row.diagnostic.path?.value != editor.path?.value ||
                row.sourceText?.regionMatches(lineStart, line, 0, line.length) != true
            ) {
                return@mapNotNull null
            }
            val range = requireNotNull(row.diagnostic.range)
            if (completionRange != null && range.intersects(completionRange)) return@mapNotNull null
            val start = maxOf(range.startUtf16, lineStart)
            val end = minOf(range.endUtf16, lineStart + line.length)
            if (end <= start) null else IdeDiagnosticSpan(row, EditorRange(start, end))
        }

    fun draw(
        spans: List<IdeDiagnosticSpan>,
        line: String,
        lineStart: Int,
        rowTop: Int,
    ) {
        for ((row, range) in spans.sortedBy { it.row.diagnostic.severity.ordinal }) {
            val x = source.xAt(line, range.startUtf16 - lineStart)
            val right = source.xAt(line, range.endUtf16 - lineStart)
            val bounds = source.clipCode(IdeRect(x, rowTop, right, rowTop + geometry.font.cellHeight)) ?: continue
            val color = IdeSourceStyles.diagnosticColor(row.diagnostic.severity)
            var left = bounds.left
            while (row.diagnostic.highlight != ru.lazyhat.compukters.ide.analysis.EditorDiagnosticHighlight.None && left < bounds.right) {
                val top = bounds.bottom - 2 + ((left - x) / 2 and 1)
                output.fill(
                    IdeFillDraw(
                        IdeFillKind.DiagnosticUnderline,
                        IdeRect(left, top, minOf(left + 2, bounds.right), top + 1),
                        color,
                        Z_TEXT + 1,
                    ),
                )
                left += 2
            }
            output.hit(
                IdeHitTarget(
                    IdeHitAction.DiagnosticChoice,
                    bounds,
                    false,
                    row.diagnostic.message,
                    IdeFocusGroup.Page,
                    Z_TARGET,
                    diagnostic = row,
                ),
            )
        }
    }
}

internal fun EditorRange.intersects(other: EditorRange): Boolean = startUtf16 < other.endUtf16 && other.startUtf16 < endUtf16
