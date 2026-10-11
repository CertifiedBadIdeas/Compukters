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
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.analysis.presentationOrNull
import ru.lazyhat.compukters.ide.client.state.IdeDiagnostics
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_CARET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_CLIP
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_CONTENT
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_SELECTION
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TARGET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TEXT

internal class IdeEditorRenderer(
    private val output: IdeDrawBuilder,
    private val geometry: IdeRenderGeometry,
    private val editor: IdeEditorView.Text,
) {
    private val font = geometry.font
    private val source = IdeSourceGeometry(editor, geometry)

    fun draw(
        caretVisible: Boolean,
        diagnostics: IdeDiagnostics,
    ) {
        val bounds = geometry.editor
        editor.find?.let { IdeFindRenderer(output, geometry).draw(it) }
        output.scissor(IdeScissorDraw(IdeScissorKind.Editor, bounds, Z_CLIP))
        val rows = source.visibleRows
        val gutterDigits = source.gutterDigits
        val codeLeft = source.codeLeft
        val counts = (editor.analysis as? IdeAnalysisState.Active)?.presentation?.methodUsages.orEmpty()
        val completion = (editor.analysis as? IdeAnalysisState.Active)?.completion
        var usageIndex = 0
        repeat(rows) { visibleIndex ->
            val lineNumber = editor.firstVisibleLine + visibleIndex
            val line = editor.visibleLines[visibleIndex]
            val lineStart = editor.visibleLineStartsUtf16[visibleIndex]
            val rowTop = source.rowTop(visibleIndex)
            val y = rowTop + font.glyphDrawOffsetY
            val completionRange =
                completion?.replacement?.let { range ->
                    val localStart = range.startUtf16 - lineStart
                    val start = if (localStart > 0 && line.getOrNull(localStart - 1) == '.') range.startUtf16 - 1 else range.startUtf16
                    EditorRange(start, range.endUtf16)
                }
            val diagnostic =
                diagnostics.rows
                    .filter { it.navigable && it.diagnostic.path?.value == editor.path?.value && it.line == lineNumber }
                    .filterNot { row ->
                        val range = row.diagnostic.range
                        if (range == null || completionRange == null) return@filterNot false
                        val overlaps = range.startUtf16 < completionRange.endUtf16 && completionRange.startUtf16 < range.endUtf16
                        overlaps
                    }.maxByOrNull { it.diagnostic.severity.ordinal }
            diagnostic?.let { row ->
                val left = bounds.left + gutterDigits * font.cellWidth
                output.fill(
                    IdeFillDraw(
                        IdeFillKind.DiagnosticMarker,
                        IdeRect(left + 3, rowTop + 3, left + 7, rowTop + font.cellHeight - 3),
                        IdeSourceStyles.diagnosticColor(row.diagnostic.severity),
                        Z_CONTENT,
                    ),
                )
                output.hit(
                    IdeHitTarget(
                        IdeHitAction.DiagnosticChoice,
                        IdeRect(left, rowTop, codeLeft, rowTop + font.cellHeight),
                        true,
                        row.diagnostic.message,
                        IdeFocusGroup.Page,
                        Z_TARGET,
                        diagnostic = row,
                    ),
                )
            }
            val nextLineStart = editor.visibleLineStartsUtf16.getOrNull(visibleIndex + 1)
            val caretBelongsToLine =
                editor.caretUtf16 >= lineStart &&
                    (nextLineStart?.let { editor.caretUtf16 < it } ?: (editor.caretUtf16 <= lineStart + line.length))
            if (caretBelongsToLine) {
                output.fill(
                    IdeFillDraw(
                        IdeFillKind.CurrentLine,
                        IdeRect(bounds.left, rowTop, bounds.right, rowTop + font.cellHeight),
                        IdeColors.CURRENT_LINE,
                        Z_SELECTION - 2,
                    ),
                )
            }
            output.code(
                IdeTextKind.LineNumber,
                (lineNumber + 1).toString().padStart(gutterDigits),
                bounds.left,
                y,
                IdeColors.LINE_NUMBER,
                bounds,
            )
            editor.gitLineChanges.forEach { change ->
                val deleted = change.kind == ru.lazyhat.compukters.ide.git.GitLineChangeKind.Deleted
                val anchor = change.firstLine.coerceAtMost(editor.totalLines - 1)
                val applies =
                    if (deleted) {
                        lineNumber == anchor
                    } else {
                        lineNumber >= change.firstLine &&
                            lineNumber.toLong() < change.firstLine.toLong() + change.lineCount
                    }
                if (applies) {
                    val top = if (deleted && change.firstLine >= editor.totalLines) rowTop + font.cellHeight - 2 else rowTop
                    val marker = IdeRect(codeLeft - 5, top, codeLeft - 2, if (deleted) top + 2 else rowTop + font.cellHeight)
                    val color =
                        when (change.kind) {
                            ru.lazyhat.compukters.ide.git.GitLineChangeKind.Added -> IdeColors.GIT_ADDED
                            ru.lazyhat.compukters.ide.git.GitLineChangeKind.Modified -> IdeColors.GIT_MODIFIED
                            ru.lazyhat.compukters.ide.git.GitLineChangeKind.Deleted -> IdeColors.GIT_DELETED
                        }
                    output.fill(IdeFillDraw(IdeFillKind.GitChange, marker, color, Z_TEXT))
                }
            }
            occurrenceHighlights(editor, line, lineStart, codeLeft, rowTop)
            selection(editor, line, lineStart, rowTop)
            styledLine(editor, lineNumber, line, lineStart, y)
            while (usageIndex < counts.size && counts[usageIndex].range.startUtf16 < lineStart) usageIndex++
            var usageX = codeLeft + (visualColumns(line) - editor.firstVisibleColumn + 2) * font.cellWidth
            while (usageIndex < counts.size && counts[usageIndex].range.startUtf16 < lineStart + line.length) {
                val index = usageIndex++
                val usage = counts[index]
                if (usage.count == 0) continue
                val label = "${usage.count} ${if (usage.count == 1) "usage" else "usages"}"
                val x = usageX
                val right = x + label.length * font.cellWidth
                if (x >= codeLeft && right <= bounds.right - font.cellWidth) {
                    output.code(IdeTextKind.MethodUsageCount, label, x, y, IdeColors.MUTED, bounds)
                    output.hit(
                        IdeHitTarget(
                            IdeHitAction.MethodUsages,
                            IdeRect(x, rowTop, right, rowTop + font.cellHeight),
                            true,
                            "Find Usages (Alt+F7)",
                            IdeFocusGroup.Page,
                            Z_TARGET,
                            choiceIndex = index,
                        ),
                    )
                }
                usageX = right + 2 * font.cellWidth
            }
            if (caretVisible && caretBelongsToLine) {
                val local = (editor.caretUtf16 - lineStart).coerceAtMost(line.length)
                val x = source.xAt(line, local)
                output.fill(IdeFillDraw(IdeFillKind.Caret, IdeRect(x, rowTop, x + 1, rowTop + font.cellHeight), IdeColors.CARET, Z_CARET))
            }
        }
        IdeEditorPopups(output, geometry, source).draw(editor)
    }

    private fun selection(
        editor: IdeEditorView.Text,
        line: String,
        lineStart: Int,
        rowTop: Int,
    ) {
        val start = editor.selectionStartUtf16 ?: return
        val end = editor.selectionEndUtf16 ?: return
        val localStart = (start - lineStart).coerceIn(0, line.length)
        val localEnd = (end - lineStart).coerceIn(0, line.length)
        if (localEnd <= localStart) return
        val left = source.xAt(line, localStart)
        val right = source.xAt(line, localEnd)
        output.fill(
            IdeFillDraw(
                IdeFillKind.Selection,
                IdeRect(left, rowTop, right, rowTop + font.cellHeight),
                IdeColors.SELECTION,
                Z_SELECTION,
            ),
        )
    }

    private fun occurrenceHighlights(
        editor: IdeEditorView.Text,
        line: String,
        lineStart: Int,
        codeLeft: Int,
        rowTop: Int,
    ) {
        val searching = editor.find != null
        val matches = editor.find?.matches ?: editor.occurrenceRanges
        val insertion = matches.binarySearch { if (it.endUtf16 <= lineStart) -1 else 1 }
        var index = -insertion - 1
        while (index < matches.size && matches[index].startUtf16 < lineStart + line.length) {
            val match = matches[index++]
            val start = (match.startUtf16 - lineStart).coerceIn(0, line.length)
            val end = (match.endUtf16 - lineStart).coerceIn(start, line.length)
            val left = source.xAt(line, start).coerceIn(codeLeft, geometry.editor.right)
            val right = source.xAt(line, end).coerceIn(left, geometry.editor.right)
            output.fill(
                IdeFillDraw(
                    if (searching) IdeFillKind.SearchMatch else IdeFillKind.WordOccurrence,
                    IdeRect(left, rowTop, right, rowTop + font.cellHeight),
                    if (searching) IdeColors.SEARCH_MATCH else IdeColors.WORD_OCCURRENCE,
                    Z_SELECTION - 1,
                ),
            )
        }
    }

    private fun styledLine(
        editor: IdeEditorView.Text,
        lineIndex: Int,
        line: String,
        lineStart: Int,
        y: Int,
    ) {
        if (line.isEmpty()) return
        val lexical = editor.lexical.lines.getOrNull(lineIndex)
        val semantic = editor.analysis.presentationOrNull()
        val link =
            ((editor.analysis as? IdeAnalysisState.Active)?.interaction as? IdeSemanticInteraction.Link)
                ?.takeIf { it.anchor.path.value == editor.path?.value }
                ?.anchor
        val projectPath = editor.path
        val boundaries = sortedSetOf(0, line.length)
        lexical?.spans?.forEach { span ->
            boundaries += span.startUtf16.coerceIn(0, line.length)
            boundaries += span.endUtf16.coerceIn(0, line.length)
        }
        (editor.analysis as? IdeAnalysisState.Active)
            ?.presentation
            ?.semanticTokens
            ?.filter { projectPath != null && it.path.value == projectPath.value }
            ?.forEach { token ->
                boundaries += (token.range.startUtf16 - lineStart).coerceIn(0, line.length)
                boundaries += (token.range.endUtf16 - lineStart).coerceIn(0, line.length)
            }
        link?.tokenRange?.let { range ->
            boundaries += (range.startUtf16 - lineStart).coerceIn(0, line.length)
            boundaries += (range.endUtf16 - lineStart).coerceIn(0, line.length)
        }
        boundaries.zipWithNext().forEach { (start, end) ->
            if (end <= start) return@forEach
            val lexicalKind = lexical?.spans?.firstOrNull { start >= it.startUtf16 && start < it.endUtf16 }?.kind
            val semanticToken =
                semantic
                    ?.semanticTokens
                    ?.firstOrNull { token ->
                        projectPath != null &&
                            token.path.value == projectPath.value &&
                            token.category.contributesTextStyle() &&
                            lineStart + start in token.range.startUtf16 until token.range.endUtf16
                    }
            val resolved =
                semanticToken?.let { IdeTextStyle.Semantic(it.category, it.isMutable) }
                    ?: lexicalKind?.let(IdeTextStyle::Lexical)
                    ?: IdeTextStyle.Plain
            val x = source.xAt(line, start)
            val absoluteRange = EditorRange(lineStart + start, lineStart + end)
            val linked =
                link?.tokenRange?.let { absoluteRange.startUtf16 >= it.startUtf16 && absoluteRange.endUtf16 <= it.endUtf16 } == true
            output.code(
                IdeTextKind.Source,
                IdeSourceColumns.glyphs(line.substring(start, end), IdeSourceColumns.columnAt(line, start)),
                x,
                y,
                if (linked) IdeColors.HYPERLINK else IdeSourceStyles.styleColor(resolved),
                geometry.editor,
                resolved,
                absoluteRange,
            )
            if (linked) {
                underline(
                    IdeFillKind.HyperlinkUnderline,
                    x,
                    IdeSourceColumns.columnAt(line, end) - IdeSourceColumns.columnAt(line, start),
                    y,
                    IdeColors.HYPERLINK,
                )
            } else if ((resolved as? IdeTextStyle.Semantic)?.isMutable == true) {
                underline(
                    IdeFillKind.MutableUnderline,
                    x,
                    IdeSourceColumns.columnAt(line, end) - IdeSourceColumns.columnAt(line, start),
                    y,
                    IdeColors.MUTABLE_UNDERLINE,
                )
            }
        }
    }

    private fun underline(
        kind: IdeFillKind,
        x: Int,
        columns: Int,
        textY: Int,
        color: Int,
    ) {
        val left = maxOf(x, geometry.editor.left)
        val right = minOf(x + columns * font.cellWidth, geometry.editor.right)
        if (right <= left) return
        val top = (textY - font.glyphDrawOffsetY + font.cellHeight - 1).coerceIn(geometry.editor.top, geometry.editor.bottom - 1)
        output.fill(
            IdeFillDraw(
                kind,
                IdeRect(left, top, right, top + 1),
                color,
                Z_TEXT,
            ),
        )
    }

    private fun visualColumns(value: String): Int = IdeSourceColumns.columnAt(value)
}
