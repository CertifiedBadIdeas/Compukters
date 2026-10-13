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

import ru.lazyhat.compukters.ide.documentation.KDocDocument
import ru.lazyhat.compukters.ide.documentation.KDocTagKind

internal data class IdeDocumentationSpan(
    val text: String,
    val color: Int = IdeColors.TEXT,
)

internal data class IdeDocumentationLine(
    val spans: List<IdeDocumentationSpan>,
    val code: Boolean = false,
) {
    val text: String get() = spans.joinToString("") { it.text }
}

/** English presentation only: the parser contains no translated labels. */
internal object IdeKDocPresentation {
    fun lines(text: String): List<IdeDocumentationLine> =
        buildList {
            val document = KDocDocument.parse(text)
            addAll(markdown(document.description))
            for (kind in KDocTagKind.entries) {
                val tags = document.tags.filter { it.kind == kind }
                if (tags.isEmpty()) continue
                if (isNotEmpty()) add(line(""))
                add(line(label(kind), IdeColors.MUTED))
                for (tag in tags) {
                    val description = markdown(tag.description)
                    val subject = if (kind == KDocTagKind.Unknown) "@${tag.sourceName}" else tag.subject
                    if (subject == null) {
                        addAll(description)
                    } else {
                        val prefix = IdeDocumentationSpan("  $subject", IdeColors.ACCENT)
                        val first = description.firstOrNull()
                        if (first != null && !first.code && first.text.isNotEmpty()) {
                            add(
                                IdeDocumentationLine(
                                    listOf(prefix, IdeDocumentationSpan(" — ", IdeColors.MUTED)) + first.spans,
                                ),
                            )
                            addAll(description.drop(1))
                        } else {
                            add(IdeDocumentationLine(listOf(prefix)))
                            addAll(description)
                        }
                    }
                }
            }
        }

    fun wrap(
        content: List<IdeDocumentationLine>,
        columns: Int,
    ): List<IdeDocumentationLine> =
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

    private fun label(kind: KDocTagKind): String =
        when (kind) {
            KDocTagKind.Param -> "Parameters"
            KDocTagKind.Return -> "Returns"
            KDocTagKind.Throws -> "Throws"
            KDocTagKind.Receiver -> "Receiver"
            KDocTagKind.Property -> "Properties"
            KDocTagKind.Constructor -> "Constructor"
            KDocTagKind.Sample -> "Samples"
            KDocTagKind.See -> "See also"
            KDocTagKind.Author -> "Author"
            KDocTagKind.Since -> "Since"
            KDocTagKind.Suppress -> "Excluded from generated documentation"
            KDocTagKind.Unknown -> "Other documentation"
        }

    private fun line(
        text: String,
        color: Int = IdeColors.TEXT,
    ) = IdeDocumentationLine(listOf(IdeDocumentationSpan(text, color)))

    private fun codeLine(text: String): IdeDocumentationLine {
        val tokens =
            ru.lazyhat.compukters.ide.highlight.KotlinLineLexer
                .scan(text)
                .spans
        val spans = mutableListOf<IdeDocumentationSpan>()
        var offset = 0
        for (token in tokens) {
            if (token.startUtf16 > offset) spans += IdeDocumentationSpan(text.substring(offset, token.startUtf16))
            spans +=
                IdeDocumentationSpan(
                    text.substring(token.startUtf16, token.endUtf16),
                    IdeSourceStyles.styleColor(IdeTextStyle.Lexical(token.kind)),
                )
            offset = token.endUtf16
        }
        if (offset < text.length) spans += IdeDocumentationSpan(text.substring(offset))
        return IdeDocumentationLine(spans, code = true)
    }

    private fun markdown(text: String): List<IdeDocumentationLine> =
        buildList {
            var fence: Char? = null
            var fenceLength = 0
            val paragraph = StringBuilder()

            fun flush() {
                if (paragraph.isNotEmpty()) {
                    add(IdeDocumentationLine(inline(paragraph.toString())))
                    paragraph.clear()
                }
            }
            for (raw in text.lines()) {
                val trimmed = raw.trimStart()
                val marker = trimmed.firstOrNull()
                val indentation = raw.takeWhile(Char::isWhitespace)
                val blockStart = indentation.length < 4 && '\t' !in indentation
                val count = if (blockStart && (marker == '`' || marker == '~')) trimmed.takeWhile { it == marker }.length else 0
                if (fence != null) {
                    if (marker == fence && count >= fenceLength && trimmed.drop(count).isBlank()) {
                        fence = null
                    } else {
                        add(codeLine(raw))
                    }
                } else if (count >= 3) {
                    flush()
                    fence = marker
                    fenceLength = count
                } else if (raw.startsWith("    ") || raw.startsWith('\t')) {
                    flush()
                    add(codeLine(raw.removePrefix("    ").removePrefix("\t")))
                } else if (raw.isBlank()) {
                    flush()
                    if (isNotEmpty() && last().text.isNotEmpty()) add(line(""))
                } else if (trimmed.startsWith('#')) {
                    flush()
                    add(line(trimmed.trimStart('#').trimStart(), IdeColors.ACCENT))
                } else if (LIST.containsMatchIn(trimmed) || trimmed.startsWith('>')) {
                    flush()
                    add(IdeDocumentationLine(inline(raw.replaceFirst(Regex("^(\\s*)[-*+] "), "$1• "))))
                } else {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(raw.trim())
                    if (raw.endsWith("  ")) flush()
                }
            }
            flush()
            while (isNotEmpty() && last().text.isEmpty()) removeAt(lastIndex)
        }

    private fun inline(text: String): List<IdeDocumentationSpan> =
        buildList {
            var offset = 0
            val plain = StringBuilder()

            fun flush() {
                if (plain.isNotEmpty()) {
                    add(IdeDocumentationSpan(plain.toString()))
                    plain.clear()
                }
            }
            while (offset < text.length) {
                val start = offset
                val ch = text[offset]
                if (ch == '\\' && offset + 1 < text.length) {
                    plain.append(text[offset + 1])
                    offset += 2
                    continue
                }
                if (ch == '`' || ch == '*' || (ch == '_' && (offset == 0 || !text[offset - 1].isLetterOrDigit()))) {
                    var count = 1
                    while (offset + count < text.length && text[offset + count] == ch) count++
                    val delimiter = ch.toString().repeat(count)
                    val end = text.indexOf(delimiter, offset + count)
                    if (end > offset + count) {
                        flush()
                        add(IdeDocumentationSpan(text.substring(offset + count, end), IdeColors.ACCENT))
                        offset = end + count
                        continue
                    }
                }
                if (ch == '[') {
                    val end = text.indexOf(']', offset + 1)
                    if (end > offset + 1) {
                        var after = end + 1
                        if (after < text.length && (text[after] == '[' || text[after] == '(')) {
                            val close = if (text[after] == '[') ']' else ')'
                            val targetEnd = text.indexOf(close, after + 1)
                            if (targetEnd >= 0) after = targetEnd + 1
                        }
                        flush()
                        add(IdeDocumentationSpan(text.substring(offset + 1, end), IdeColors.ACCENT))
                        offset = after
                        continue
                    }
                }
                plain.append(text[start])
                offset++
            }
            flush()
        }

    private val LIST = Regex("^(?:[-*+] |[0-9]+[.)] )")
}
