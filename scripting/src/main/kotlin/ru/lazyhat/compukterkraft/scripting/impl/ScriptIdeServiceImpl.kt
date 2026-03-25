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

package ru.lazyhat.compukterkraft.scripting.impl

import ru.lazyhat.compukterkraft.lang.api.TokenKind
import ru.lazyhat.compukterkraft.lang.frontend.SymbolKind
import ru.lazyhat.compukterkraft.scripting.api.CompletionItem
import ru.lazyhat.compukterkraft.scripting.api.CompletionItemKind
import ru.lazyhat.compukterkraft.scripting.api.DefinitionTarget
import ru.lazyhat.compukterkraft.scripting.api.Diagnostic
import ru.lazyhat.compukterkraft.scripting.api.HighlightToken
import ru.lazyhat.compukterkraft.scripting.api.HighlightTokenType
import ru.lazyhat.compukterkraft.scripting.api.HoverInfo
import ru.lazyhat.compukterkraft.scripting.api.ScriptIdeService

class ScriptIdeServiceImpl(
    private val environment: ScriptingEnvironmentImpl,
) : ScriptIdeService {
    override fun analyze(
        name: String,
        code: String,
    ): List<Diagnostic> = environment.frontend.analyze(name, code).diagnostics.map { it.toSharedDiagnostic() }

    override fun highlight(
        name: String,
        code: String,
    ): List<HighlightToken> {
        val analysis = environment.frontend.analyze(name, code)
        val lexicalTokens =
            analysis.tokens.mapNotNull { token ->
                val type =
                    when (token.kind) {
                        TokenKind.FUN,
                        TokenKind.LET,
                        TokenKind.VAR,
                        TokenKind.IF,
                        TokenKind.ELSE,
                        TokenKind.WHILE,
                        TokenKind.RETURN,
                        TokenKind.IMPORT,
                        TokenKind.RECORD,
                        TokenKind.TRUE,
                        TokenKind.FALSE,
                        TokenKind.NULL,
                        -> HighlightTokenType.KEYWORD
                        TokenKind.STRING -> HighlightTokenType.STRING
                        TokenKind.NUMBER -> HighlightTokenType.NUMBER
                        else -> null
                    }
                type?.let { HighlightToken(token.range.toSharedRange(), it) }
            }
        val semanticTokens =
            analysis.symbols.mapNotNull { symbol ->
                val range = symbol.range ?: return@mapNotNull null
                val type =
                    when (symbol.kind) {
                        SymbolKind.FIELD,
                        SymbolKind.VARIABLE,
                        SymbolKind.PARAMETER,
                        -> HighlightTokenType.PROPERTY
                        SymbolKind.FUNCTION,
                        SymbolKind.BUILTIN_FUNCTION,
                        -> HighlightTokenType.FUNCTION
                        SymbolKind.RECORD,
                        SymbolKind.BUILTIN_TYPE,
                        -> HighlightTokenType.TYPE
                        SymbolKind.MODULE -> null
                    }
                type?.let { HighlightToken(range.toSharedRange(), it) }
            }
        return (lexicalTokens + semanticTokens).distinctBy { it.range to it.type }
    }

    override fun complete(
        name: String,
        code: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> {
        val analysis = environment.frontend.analyze(name, code)
        val offset = offsetFor(code, line, column)
        val prefix = prefixAt(code, offset)
        val moduleQualifier = moduleQualifierAt(code, offset)
        val items =
            if (moduleQualifier != null) {
                analysis.moduleMembers(moduleQualifier).map {
                    CompletionItem(
                        label = it.name,
                        insertText = it.name,
                        detail = it.detail,
                        kind = CompletionItemKind.SYMBOL,
                    )
                }
            } else {
                analysis.visibleSymbolsAt(offset).map {
                    CompletionItem(
                        label = it.name,
                        insertText = it.name,
                        detail = it.detail,
                        kind =
                            when (it.kind) {
                                SymbolKind.MODULE -> CompletionItemKind.IMPORT
                                SymbolKind.BUILTIN_FUNCTION -> CompletionItemKind.SNIPPET
                                else -> CompletionItemKind.SYMBOL
                            },
                    )
                } + KEYWORDS.map {
                    CompletionItem(
                        label = it,
                        detail = "Language keyword",
                        kind = CompletionItemKind.KEYWORD,
                    )
                }
            }
        return items
            .distinctBy { it.label }
            .filter { prefix.isBlank() || it.label.startsWith(prefix) }
            .sortedBy { it.label }
    }

    override fun hover(
        name: String,
        code: String,
        line: Int,
        column: Int,
    ): HoverInfo? {
        val analysis = environment.frontend.analyze(name, code)
        val offset = offsetFor(code, line, column)
        val symbol = analysis.symbolAt(offset) ?: return null
        val text =
            buildString {
                append(symbol.detail)
                symbol.documentation?.let {
                    appendLine()
                    append(it)
                }
            }
        return HoverInfo(text, symbol.range?.toSharedRange())
    }

    override fun definition(
        name: String,
        code: String,
        line: Int,
        column: Int,
    ): DefinitionTarget? {
        val analysis = environment.frontend.analyze(name, code)
        val offset = offsetFor(code, line, column)
        val symbol = analysis.referenceAt(offset)?.target ?: return null
        val range = symbol.range ?: return null
        return DefinitionTarget(name, range.toSharedRange())
    }

    private fun offsetFor(
        code: String,
        line: Int,
        column: Int,
    ): Int {
        var currentLine = 0
        var currentColumn = 0
        code.forEachIndexed { index, ch ->
            if (currentLine == line && currentColumn == column) {
                return index
            }
            if (ch == '\n') {
                currentLine += 1
                currentColumn = 0
            } else {
                currentColumn += 1
            }
        }
        return code.length
    }

    private fun prefixAt(
        code: String,
        offset: Int,
    ): String {
        var start = offset.coerceAtMost(code.length)
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) {
            start -= 1
        }
        return code.substring(start, offset.coerceAtMost(code.length))
    }

    private fun moduleQualifierAt(
        code: String,
        offset: Int,
    ): String? {
        val safeOffset = offset.coerceAtMost(code.length)
        var cursor = safeOffset
        while (cursor > 0 && (code[cursor - 1].isLetterOrDigit() || code[cursor - 1] == '_')) {
            cursor -= 1
        }
        if (cursor == 0 || code[cursor - 1] != '.') return null
        val end = cursor - 1
        var start = end
        while (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) {
            start -= 1
        }
        return code.substring(start, end)
    }

    private companion object {
        val KEYWORDS = listOf("fun", "let", "var", "if", "else", "while", "return", "import", "record", "true", "false", "null")
    }
}
