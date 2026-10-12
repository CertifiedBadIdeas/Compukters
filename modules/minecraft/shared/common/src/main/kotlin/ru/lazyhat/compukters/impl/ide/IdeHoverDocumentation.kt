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

import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorExpressionInfo

/** One screen owns one documentation layout; repeated frames do not reparse long KDoc. */
class IdeHoverDocumentation(
    val info: EditorExpressionInfo,
) {
    internal var widthColumns = 0
        private set
    private var cachedColumns = -1
    private var cachedLines: List<IdeDocumentationLine> = emptyList()

    internal fun lines(columns: Int): List<IdeDocumentationLine> {
        if (cachedColumns == columns) return cachedLines
        val content =
            buildList {
                add(IdeDocumentationLine(listOf(IdeDocumentationSpan(info.signature ?: info.renderedType, IdeColors.ACCENT))))
                info.documentation?.takeIf { it.isNotBlank() }?.let {
                    add(IdeDocumentationLine(emptyList()))
                    addAll(IdeKDocPresentation.lines(it))
                }
                info.origin?.let { origin ->
                    val label =
                        when (origin) {
                            DeclarationOrigin.Project -> "Project source"
                            is DeclarationOrigin.Platform -> "Library · ${origin.identity.name}"
                        }
                    add(IdeDocumentationLine(emptyList()))
                    add(IdeDocumentationLine(listOf(IdeDocumentationSpan(label, IdeColors.MUTED))))
                }
            }
        val lines =
            content.flatMap { line ->
                val value = line.text.replace("\t", "    ")
                // All lines remain available; wrapping is bounded by the admitted documentation byte budget.
                IdePopupText.wrap(value, columns, value.length + 1).map { range ->
                    val spans = mutableListOf<IdeDocumentationSpan>()
                    var offset = 0
                    for (span in line.spans) {
                        val expanded = span.text.replace("\t", "    ")
                        val start = maxOf(range.startUtf16, offset)
                        val end = minOf(range.endUtf16, offset + expanded.length)
                        if (start < end) spans += IdeDocumentationSpan(expanded.substring(start - offset, end - offset), span.color)
                        offset += expanded.length
                    }
                    IdeDocumentationLine(spans, line.code)
                }
            }
        cachedColumns = columns
        cachedLines = lines
        widthColumns = lines.maxOfOrNull { IdeSourceColumns.columnAt(it.text) } ?: 0
        return lines
    }
}
