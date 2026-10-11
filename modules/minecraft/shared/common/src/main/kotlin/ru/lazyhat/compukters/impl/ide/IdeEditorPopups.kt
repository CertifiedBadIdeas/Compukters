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

import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeDeclarationTarget
import ru.lazyhat.compukters.ide.client.analysis.IdeParameterInfoState
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_POPUP
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_POPUP_TEXT

internal class IdeEditorPopups(
    private val output: IdeDrawBuilder,
    private val geometry: IdeRenderGeometry,
    private val source: IdeSourceGeometry,
) {
    private val font = geometry.font

    fun draw(
        editor: IdeEditorView.Text,
        diagnostics: ru.lazyhat.compukters.ide.client.state.IdeDiagnostics,
    ) {
        val active = editor.analysis as? IdeAnalysisState.Active
        when (val interaction = active?.interaction) {
            is IdeSemanticInteraction.Chooser -> {
                semanticChooser(interaction)
            }

            else -> {
                val parameterInfo = active?.parameterInfo
                if (parameterInfo != null) {
                    parameterInfo(parameterInfo)
                } else {
                    completion(editor)
                }
                if (active?.completion == null && active?.parameterInfo == null && interaction is IdeSemanticInteraction.Hover) {
                    if (diagnostics.rows.none {
                            it.navigable && it.diagnostic.path?.value == interaction.anchor.path.value &&
                                it.diagnostic.range?.intersects(interaction.anchor.tokenRange) == true
                        }
                    ) {
                        semanticHover(interaction)
                    }
                }
            }
        }
    }

    private fun semanticHover(hover: IdeSemanticInteraction.Hover) {
        val anchor = source.anchor(hover.anchor.tokenRange) ?: return
        val availableHeight = maxOf(anchor.top - geometry.editor.top, geometry.editor.bottom - anchor.bottom)
        val maximumRows = minOf(20, (availableHeight - POPUP_VERTICAL_PADDING) / font.cellHeight)
        if (maximumRows <= 0) return
        val maximumColumns = minOf(80, ((geometry.editor.width - POPUP_HORIZONTAL_PADDING) / font.cellWidth).coerceAtLeast(1))
        val content =
            buildList {
                hover.info.signature?.let { add(it to IdeColors.ACCENT) }
                add("Type: ${hover.info.renderedType}" to IdeColors.TEXT)
                hover.info.origin?.let { origin ->
                    add(
                        when (origin) {
                            DeclarationOrigin.Project -> "Project source"
                            is DeclarationOrigin.Platform -> "Library · ${origin.identity.name}"
                        } to IdeColors.MUTED,
                    )
                }
                hover.info.documentation?.let { documentation ->
                    add("" to IdeColors.TEXT)
                    add(documentation.replace("\t", "    ") to IdeColors.TEXT)
                }
            }
        val lines =
            content
                .flatMap { (value, color) ->
                    IdePopupText.wrap(value, maximumColumns, maximumRows + 1).map { range ->
                        value.substring(range.startUtf16, range.endUtf16).trimEnd() to color
                    }
                }.toMutableList()
        if (lines.size > maximumRows) {
            lines.subList(maximumRows, lines.size).clear()
            lines[lines.lastIndex] = popupText(lines.last().first + "…") to lines.last().second
        }
        if (lines.isEmpty()) return
        val requestedWidth =
            maxOf(
                SEMANTIC_POPUP_MINIMUM_WIDTH,
                lines.maxOf { visualColumns(it.first) } * font.cellWidth + POPUP_HORIZONTAL_PADDING,
            )
        val popup = geometry.anchoredPopup(anchor, requestedWidth, lines.size * font.cellHeight + POPUP_VERTICAL_PADDING)
        semanticPopupPanel(popup.bounds)
        lines.forEachIndexed { index, (value, color) ->
            output.code(
                IdeTextKind.Hover,
                value,
                popup.bounds.left + 4,
                popup.bounds.top + POPUP_VERTICAL_PADDING / 2 + font.glyphDrawOffsetY + index * font.cellHeight,
                color,
                popup.bounds,
                z = Z_POPUP_TEXT,
            )
        }
    }

    private fun semanticChooser(chooser: IdeSemanticInteraction.Chooser) {
        val anchor = source.anchor(chooser.anchor.tokenRange) ?: return
        val labels = chooser.targets.map(::declarationLabel).map(::popupText)
        val requestedWidth =
            maxOf(
                SEMANTIC_POPUP_MINIMUM_WIDTH,
                labels.maxOf(::visualColumns) * font.cellWidth + POPUP_HORIZONTAL_PADDING,
            )
        val requestedRows = minOf(DECLARATION_VISIBLE_ROWS, labels.size)
        val popup = geometry.anchoredPopup(anchor, requestedWidth, requestedRows * UI_LINE_HEIGHT + POPUP_VERTICAL_PADDING)
        val visibleRows = ((popup.bounds.height - POPUP_VERTICAL_PADDING) / UI_LINE_HEIGHT).coerceAtLeast(1)
        val first = (chooser.selectedIndex - visibleRows + 1).coerceIn(0, (labels.size - visibleRows).coerceAtLeast(0))
        val last = minOf(labels.size, first + visibleRows)
        semanticPopupPanel(popup.bounds)
        for (index in first until last) {
            val row = index - first
            val rowBounds =
                IdeRect(
                    popup.bounds.left,
                    popup.bounds.top + POPUP_VERTICAL_PADDING / 2 + row * UI_LINE_HEIGHT,
                    popup.bounds.right,
                    minOf(popup.bounds.bottom, popup.bounds.top + POPUP_VERTICAL_PADDING / 2 + (row + 1) * UI_LINE_HEIGHT),
                )
            if (rowBounds.height <= 0) continue
            val selected = index == chooser.selectedIndex
            if (selected) {
                output.fill(IdeFillDraw(IdeFillKind.Selection, rowBounds, IdeColors.SELECTION, Z_POPUP))
            }
            output.ui(
                IdeTextKind.DeclarationChoice,
                labels[index],
                popup.bounds.left + 4,
                popup.bounds.top + POPUP_VERTICAL_PADDING / 2 + row * UI_LINE_HEIGHT,
                if (selected) IdeColors.ACCENT else IdeColors.TEXT,
                popup.bounds,
                Z_POPUP_TEXT,
            )
            output.hit(
                IdeHitTarget(
                    IdeHitAction.DeclarationChoice,
                    rowBounds,
                    enabled = true,
                    tooltip = null,
                    focusGroup = IdeFocusGroup.Page,
                    zIndex = Z_POPUP_TEXT,
                    selected = selected,
                    choiceIndex = index,
                ),
            )
        }
    }

    private fun semanticPopupPanel(bounds: IdeRect) {
        output.panel(IdePanelKind.Dialog, bounds, IdeColors.PANEL_ALT, Z_POPUP)
        output.scissor(IdeScissorDraw(IdeScissorKind.SemanticPopup, bounds, Z_POPUP))
    }

    private fun declarationLabel(target: IdeDeclarationTarget): String =
        when (target) {
            is IdeDeclarationTarget.Project -> "Project · ${target.path.value}"
            is IdeDeclarationTarget.AttachedSource -> "${target.module.name} · ${target.path.value}"
        }

    private fun popupText(value: String): String {
        val maximumColumns = (geometry.editor.width / font.cellWidth - 2).coerceAtLeast(1)
        if (visualColumns(value) <= maximumColumns) return value
        val result = StringBuilder(minOf(value.length, maximumColumns))
        var offset = 0
        var columns = 0
        while (offset < value.length && columns < maximumColumns - 1) {
            val codePoint = value.codePointAt(offset)
            result.appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
            columns++
        }
        return result.append('…').toString()
    }

    private fun completion(editor: IdeEditorView.Text) {
        val completion = (editor.analysis as? IdeAnalysisState.Active)?.completion ?: return
        val caret = source.caret() ?: return
        val visibleItems = completion.visibleEntries
        val rows = visibleItems.map(::completionRow)
        val results =
            visibleItems.map {
                it.proposal.callablePresentation
                    ?.returnType
                    .orEmpty()
            }
        val resultColumns = results.maxOf(::visualColumns)
        val contentColumns = rows.maxOf(::visualColumns) + 2 + if (resultColumns > 0) resultColumns + 2 else 0
        val contentWidth = contentColumns * font.cellWidth + COMPLETION_HORIZONTAL_PADDING
        val popup =
            geometry.completionPopup(
                caret,
                maxOf(COMPLETION_MINIMUM_WIDTH, contentWidth),
                visibleItems.size * font.cellHeight + POPUP_VERTICAL_PADDING,
            )
        output.panel(IdePanelKind.Dialog, popup.bounds, IdeColors.PANEL_ALT, Z_POPUP)
        output.scissor(IdeScissorDraw(IdeScissorKind.Completion, popup.bounds, Z_POPUP))
        val innerLeft = minOf(popup.bounds.left + 4, popup.bounds.right)
        val innerRight = maxOf(innerLeft, popup.bounds.right - 4)
        val left = minOf(innerLeft + 2 * font.cellWidth, innerRight)
        val available = innerRight - left
        val resultWidth = minOf(resultColumns * font.cellWidth, available / 2 / font.cellWidth * font.cellWidth)
        val gap = if (resultWidth > 0) minOf(2 * font.cellWidth, available - resultWidth) else 0
        val leftRight = innerRight - resultWidth - gap
        val leftClip = IdeRect(left, popup.bounds.top, leftRight, popup.bounds.bottom)
        val resultClip = IdeRect(innerRight - resultWidth, popup.bounds.top, innerRight, popup.bounds.bottom)
        rows.forEachIndexed { index, row ->
            val selected = completion.firstVisibleIndex + index == completion.selectedIndex
            val y = popup.bounds.top + POPUP_VERTICAL_PADDING / 2 + font.glyphDrawOffsetY + index * font.cellHeight
            completionBadge(visibleItems[index].proposal.kind)?.let { (letter, color) ->
                output.code(IdeTextKind.CompletionBadge, letter, innerLeft, y, color, popup.bounds, z = Z_POPUP_TEXT)
            }
            val visible = completionText(row, leftClip.width)
            var offset = 0
            val nameLimit = if (visible != row && visible.endsWith('…')) visible.length - 1 else visible.length
            for (range in visibleItems[index].proposal.matchedNameRanges) {
                if (range.startUtf16 >= nameLimit) break
                val end = minOf(range.endUtf16, nameLimit)
                completionFragment(visible, offset, range.startUtf16, left, y, selected, false, leftClip)
                completionFragment(visible, range.startUtf16, end, left, y, selected, true, leftClip)
                offset = end
            }
            completionFragment(visible, offset, visible.length, left, y, selected, false, leftClip)
            if (results[index].isNotEmpty() && resultWidth > 0) {
                val result = completionText(results[index], resultWidth)
                output.code(
                    IdeTextKind.CompletionReturnType,
                    result,
                    innerRight - visualColumns(result) * font.cellWidth,
                    y,
                    if (selected) IdeColors.ACCENT else IdeColors.MUTED,
                    resultClip,
                    z = Z_POPUP_TEXT,
                )
            }
        }
    }

    private fun completionFragment(
        value: String,
        start: Int,
        end: Int,
        x: Int,
        y: Int,
        selected: Boolean,
        matched: Boolean,
        clip: IdeRect,
    ) {
        if (start >= end) return
        output.code(
            if (matched) IdeTextKind.CompletionMatch else IdeTextKind.Completion,
            value.substring(start, end),
            x + visualColumns(value.substring(0, start)) * font.cellWidth,
            y,
            if (matched) {
                IdeColors.COMPLETION_MATCH
            } else if (selected) {
                IdeColors.ACCENT
            } else {
                IdeColors.TEXT
            },
            clip,
            z = Z_POPUP_TEXT,
        )
    }

    private fun completionText(
        value: String,
        width: Int,
    ): String {
        val columns = width / font.cellWidth
        if (columns <= 0) return ""
        if (visualColumns(value) <= columns) return value
        val result = StringBuilder()
        var offset = 0
        var written = 0
        while (offset < value.length && written < columns - 1) {
            val point = value.codePointAt(offset)
            result.appendCodePoint(point)
            offset += Character.charCount(point)
            written++
        }
        return result.append('…').toString()
    }

    private fun parameterInfo(info: IdeParameterInfoState) {
        val anchor =
            source.anchor(
                ru.lazyhat.compukters.ide.editor
                    .EditorRange(info.callRange.startUtf16, info.callRange.startUtf16),
            )
                ?: source.caret() ?: return
        val availableHeight = maxOf(anchor.top - geometry.editor.top, geometry.editor.bottom - anchor.bottom)
        val maximumRows = minOf(20, (availableHeight - 12) / font.cellHeight)
        if (maximumRows < 2) return
        val columns = minOf(80, ((geometry.editor.width - 24) / font.cellWidth).coerceAtLeast(1))
        val items = info.items.sortedByDescending { it.bestCandidate }.take(minOf(PARAMETER_INFO_VISIBLE_ROWS, maximumRows - 1))
        val rowsPerItem = ((maximumRows - 1) / items.size).coerceAtLeast(1)
        val lines =
            items.flatMap { item ->
                val wrapped = IdePopupText.wrap(item.signature, columns, item.signature.length)
                val activeRow = wrapped.indexOfFirst { range -> item.activeParameter?.intersects(range) == true }.coerceAtLeast(0)
                val first = (activeRow - rowsPerItem + 1).coerceAtLeast(0)
                wrapped.drop(first).take(rowsPerItem).mapIndexed { index, range ->
                    ParameterLine(item, range, first > 0 && index == 0)
                }
            }
        val header = "Parameters · Ctrl+P" + if (info.items.size > 1) " · ${info.items.size} signatures" else ""
        val width =
            maxOf(
                SEMANTIC_POPUP_MINIMUM_WIDTH,
                maxOf(visualColumns(header), lines.maxOf { visualColumns(it.text) + if (it.truncated) 2 else 0 }) * font.cellWidth + 12,
            )
        val popup = geometry.anchoredPopup(anchor, width, (lines.size + 1) * font.cellHeight + 12)
        semanticPopupPanel(popup.bounds)
        output.code(
            IdeTextKind.ParameterInfoHeader,
            header,
            popup.bounds.left + 6,
            popup.bounds.top + 6 + font.glyphDrawOffsetY,
            IdeColors.MUTED,
            popup.bounds,
            z = Z_POPUP_TEXT,
        )
        lines.forEachIndexed { index, line ->
            val item = line.item
            val y = popup.bounds.top + 6 + font.glyphDrawOffsetY + (index + 1) * font.cellHeight
            var x = popup.bounds.left + 6
            if (line.truncated) {
                output.code(IdeTextKind.ParameterInfo, "… ", x, y, IdeColors.MUTED, popup.bounds, z = Z_POPUP_TEXT)
                x += 2 * font.cellWidth
            }
            val color = if (item.bestCandidate) IdeColors.TEXT else IdeColors.MUTED
            val parameter = item.activeParameter?.takeIf { it.intersects(line.range) }
            if (parameter == null) {
                output.code(IdeTextKind.ParameterInfo, line.text, x, y, color, popup.bounds, z = Z_POPUP_TEXT)
            } else {
                val start = maxOf(parameter.startUtf16, line.range.startUtf16) - line.range.startUtf16
                val end = minOf(parameter.endUtf16, line.range.endUtf16) - line.range.startUtf16
                val prefix = line.text.substring(0, start)
                val active = line.text.substring(start, end)
                val suffix = line.text.substring(end)
                val activeX = x + visualColumns(prefix) * font.cellWidth
                val activeBounds =
                    IdeRect(
                        activeX,
                        y - font.glyphDrawOffsetY,
                        minOf(popup.bounds.right - 4, activeX + visualColumns(active) * font.cellWidth),
                        y - font.glyphDrawOffsetY + font.cellHeight,
                    )
                if (activeBounds.width > 0) {
                    output.fill(IdeFillDraw(IdeFillKind.ParameterInfoActive, activeBounds, IdeColors.SELECTION, Z_POPUP + 1))
                }
                output.code(IdeTextKind.ParameterInfo, prefix, x, y, color, popup.bounds, z = Z_POPUP_TEXT)
                output.code(IdeTextKind.ParameterInfo, active, activeX, y, IdeColors.ACCENT, popup.bounds, z = Z_POPUP_TEXT)
                output.code(
                    IdeTextKind.ParameterInfo,
                    suffix,
                    activeX + visualColumns(active) * font.cellWidth,
                    y,
                    color,
                    popup.bounds,
                    z = Z_POPUP_TEXT,
                )
            }
        }
    }

    private data class ParameterLine(
        val item: ru.lazyhat.compukters.ide.analysis.ParameterInfoItem,
        val range: ru.lazyhat.compukters.ide.editor.EditorRange,
        val truncated: Boolean,
    ) {
        val text: String get() = item.signature.substring(range.startUtf16, range.endUtf16)
    }

    private fun completionRow(entry: ru.lazyhat.compukters.ide.client.analysis.IdeCompletionEntry): String =
        buildString {
            append(entry.proposal.label)
            val presentation = entry.proposal.callablePresentation
            presentation?.receiverType?.let { append(" (for $it)") }
            presentation?.packageName?.let { append(" · in $it") }
            if (completionBadge(entry.proposal.kind) == null) {
                append(" · ")
                append(
                    entry.proposal.kind.name
                        .lowercase(),
                )
            }
            entry.actionText?.let {
                append(" · ")
                append(it)
            }
        }

    private fun completionBadge(kind: CompletionKind): Pair<String, Int>? =
        when (kind) {
            CompletionKind.Function, CompletionKind.ExtensionFunction -> {
                "F" to IdeColors.COMPLETION_CALLABLE
            }

            CompletionKind.MemberFunction -> {
                "M" to IdeColors.COMPLETION_CALLABLE
            }

            CompletionKind.Property, CompletionKind.LocalVariable, CompletionKind.Parameter, CompletionKind.EnumEntry -> {
                "V" to IdeColors.COMPLETION_VARIABLE
            }

            CompletionKind.Class, CompletionKind.Object, CompletionKind.TypeParameter, CompletionKind.TypeAlias -> {
                "C" to IdeColors.COMPLETION_CLASS
            }

            CompletionKind.PeripheralProvider -> {
                "P" to IdeColors.COMPLETION_PROVIDER
            }

            CompletionKind.Interface -> {
                "I" to IdeColors.COMPLETION_INTERFACE
            }

            CompletionKind.Package, CompletionKind.Keyword -> {
                null
            }
        }

    private fun visualColumns(value: String): Int = IdeSourceColumns.columnAt(value)

    private companion object {
        const val UI_LINE_HEIGHT = 12
        private const val COMPLETION_MINIMUM_WIDTH = 220
        private const val COMPLETION_HORIZONTAL_PADDING = 8
        private const val SEMANTIC_POPUP_MINIMUM_WIDTH = 180
        private const val POPUP_HORIZONTAL_PADDING = 8
        private const val POPUP_VERTICAL_PADDING = 8
        private const val DECLARATION_VISIBLE_ROWS = 8
        private const val PARAMETER_INFO_VISIBLE_ROWS = 6
    }
}
