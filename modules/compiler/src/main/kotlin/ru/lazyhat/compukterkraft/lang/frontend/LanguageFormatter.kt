/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.lang.frontend

import ru.lazyhat.compukterkraft.lang.runtime.Diagnostic
import ru.lazyhat.compukterkraft.lang.runtime.IdeDiagnosticSeverity
import ru.lazyhat.compukterkraft.lang.runtime.TextEdit

data class FormatOptions(
    val cleanup: Boolean = false,
)

data class FormatResult(
    val edits: List<TextEdit>,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val changed: Boolean
        get() = edits.isNotEmpty()
}

class LanguageFormatter(
    private val parser: ParserFacade = DefaultParserFacade(),
) {
    fun formatDocument(
        name: String,
        source: String,
    ): FormatResult {
        val parsed = parser.parse(name, source)
        if (parsed.syntaxDiagnostics.any { it.severity == FrontendSeverity.ERROR }) {
            return cannotFormat()
        }
        val formatted = renderCanonical(parsed)
        return if (formatted == source) {
            FormatResult(emptyList())
        } else {
            FormatResult(listOf(TextEdit(0, source.length, formatted)))
        }
    }

    fun cleanupDocument(
        name: String,
        source: String,
        loader: SourceLoader = NoOpSourceLoader,
    ): FormatResult = formatDocument(name, source)

    private fun cannotFormat(): FormatResult =
        FormatResult(
            edits = emptyList(),
            diagnostics =
                listOf(
                    Diagnostic(
                        message = "Cannot format source with syntax errors.",
                        severity = IdeDiagnosticSeverity.ERROR,
                    ),
                ),
        )

    private fun renderCanonical(parsed: ParsedSource): String = parsed.source.ensureTrailingNewline()
}

private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"
