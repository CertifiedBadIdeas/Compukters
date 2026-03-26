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

package ru.lazyhat.ck.lang.frontend

import ru.lazyhat.ck.lang.api.SourceLocation
import ru.lazyhat.ck.lang.api.SourceRange
import ru.lazyhat.ck.lang.api.Token
import ru.lazyhat.ck.lang.api.TokenKind
import ru.lazyhat.compukterkraft.machine.CompletionItem
import ru.lazyhat.compukterkraft.machine.CompletionItemKind
import ru.lazyhat.compukterkraft.machine.DefinitionTarget
import ru.lazyhat.compukterkraft.machine.Diagnostic
import ru.lazyhat.compukterkraft.machine.HighlightToken
import ru.lazyhat.compukterkraft.machine.HighlightTokenKind
import ru.lazyhat.compukterkraft.machine.HoverInfo
import ru.lazyhat.compukterkraft.machine.IdeDiagnosticSeverity

class LanguageIde(
    private val frontend: LanguageFrontend = LanguageFrontend(),
) {
    fun analyze(
        name: String,
        source: String,
    ): IdeSnapshot {
        val analysis = frontend.analyze(name, source)
        return IdeSnapshot(
            diagnostics = analysis.diagnostics.map(::toDiagnostic),
            highlights = buildHighlights(analysis.tokens, analysis.references),
            analysis = analysis,
        )
    }

    fun complete(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> {
        val snapshot = analyze(name, source)
        val offset = offsetAt(source, line, column)
        val prefix = identifierPrefix(source, offset)
        val modulePrefix = moduleMemberPrefix(source, offset)
        return if (modulePrefix != null) {
            snapshot.analysis
                .moduleMembers(modulePrefix.first)
                .asSequence()
                .filter { it.name.startsWith(modulePrefix.second) }
                .map(::toCompletionItem)
                .distinctBy { it.kind to it.label }
                .toList()
        } else {
            buildList {
                addAll(
                    snapshot.analysis
                        .visibleSymbolsAt(offset)
                        .asSequence()
                        .filter { it.name.startsWith(prefix) }
                        .map(::toCompletionItem)
                        .toList(),
                )
                addAll(
                    LanguageBuiltins.registry.builtinTypes
                        .asSequence()
                        .filter { it.name.startsWith(prefix) }
                        .map {
                            CompletionItem(
                                label = it.name,
                                detail = "struct ${it.name}",
                                kind = CompletionItemKind.TYPE,
                                documentation = it.documentation,
                            )
                        }.toList(),
                )
                addAll(
                    KEYWORDS
                        .asSequence()
                        .filter { it.startsWith(prefix) }
                        .map {
                            CompletionItem(
                                label = it,
                                detail = "keyword",
                                kind = CompletionItemKind.KEYWORD,
                            )
                        }.toList(),
                )
            }.distinctBy { it.kind to it.label }
        }
    }

    fun hover(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): HoverInfo? {
        val snapshot = analyze(name, source)
        val offset = offsetAt(source, line, column)
        val reference = snapshot.analysis.referenceAt(offset)
        if (reference != null) {
            return HoverInfo(
                contents = reference.target.detail,
                documentation = reference.target.documentation,
                range = reference.range,
            )
        }
        val symbol = snapshot.analysis.symbolAt(offset) ?: return null
        return HoverInfo(
            contents = symbol.detail,
            documentation = symbol.documentation,
            range = symbol.range,
        )
    }

    fun definition(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): DefinitionTarget? {
        val snapshot = analyze(name, source)
        val offset = offsetAt(source, line, column)
        val targetRange = snapshot.analysis.referenceAt(offset)?.target?.range ?: return null
        return DefinitionTarget(path = name, range = targetRange)
    }

    data class IdeSnapshot(
        val diagnostics: List<Diagnostic>,
        val highlights: List<HighlightToken>,
        val analysis: AnalyzedProgram,
    )

    private fun toDiagnostic(diagnostic: FrontendDiagnostic): Diagnostic =
        Diagnostic(
            message = diagnostic.message,
            range = diagnostic.range,
            severity =
                when (diagnostic.severity) {
                    FrontendSeverity.INFO -> IdeDiagnosticSeverity.INFO
                    FrontendSeverity.WARNING -> IdeDiagnosticSeverity.WARNING
                    FrontendSeverity.ERROR -> IdeDiagnosticSeverity.ERROR
                },
        )

    private fun buildHighlights(
        tokens: List<Token>,
        references: List<ReferenceInfo>,
    ): List<HighlightToken> {
        val referenceByStart = references.associateBy { it.range.start.offset }
        return tokens
            .filter { it.kind != TokenKind.EOF }
            .mapNotNull { token ->
                val kind =
                    referenceByStart[token.range.start.offset]?.target?.kind?.let(::highlightKindForSymbol)
                        ?: highlightKindForToken(token.kind)
                kind?.let { HighlightToken(it, token.range) }
            }
    }

    private fun highlightKindForSymbol(kind: SymbolKind): HighlightTokenKind =
        when (kind) {
            SymbolKind.MODULE -> HighlightTokenKind.MODULE
            SymbolKind.FUNCTION,
            SymbolKind.BUILTIN_FUNCTION,
            -> HighlightTokenKind.FUNCTION
            SymbolKind.RECORD,
            SymbolKind.BUILTIN_TYPE,
            -> HighlightTokenKind.TYPE
            SymbolKind.FIELD -> HighlightTokenKind.FIELD
            SymbolKind.VARIABLE,
            SymbolKind.PARAMETER,
            -> HighlightTokenKind.IDENTIFIER
        }

    private fun highlightKindForToken(kind: TokenKind): HighlightTokenKind? =
        when (kind) {
            TokenKind.TRUE,
            TokenKind.FALSE,
            -> HighlightTokenKind.BOOLEAN
            TokenKind.NULL -> HighlightTokenKind.NULL
            TokenKind.NUMBER -> HighlightTokenKind.NUMBER
            TokenKind.STRING -> HighlightTokenKind.STRING
            TokenKind.FUN,
            TokenKind.VAL,
            TokenKind.VAR,
            TokenKind.IF,
            TokenKind.ELSE,
            TokenKind.WHILE,
            TokenKind.RETURN,
            TokenKind.IMPORT,
            TokenKind.STRUCT,
            -> HighlightTokenKind.KEYWORD
            TokenKind.PLUS,
            TokenKind.MINUS,
            TokenKind.STAR,
            TokenKind.SLASH,
            TokenKind.BANG,
            TokenKind.EQUAL,
            TokenKind.EQUAL_EQUAL,
            TokenKind.BANG_EQUAL,
            TokenKind.LT,
            TokenKind.LTE,
            TokenKind.GT,
            TokenKind.GTE,
            TokenKind.AMP_AMP,
            TokenKind.PIPE_PIPE,
            -> HighlightTokenKind.OPERATOR
            TokenKind.COLON,
            TokenKind.SEMICOLON,
            TokenKind.COMMA,
            TokenKind.DOT,
            TokenKind.QUESTION,
            TokenKind.LPAREN,
            TokenKind.RPAREN,
            TokenKind.LBRACE,
            TokenKind.RBRACE,
            -> HighlightTokenKind.PUNCTUATION
            TokenKind.IDENTIFIER -> HighlightTokenKind.IDENTIFIER
            TokenKind.EOF -> null
        }

    private fun toCompletionItem(symbol: SymbolInfo): CompletionItem =
        CompletionItem(
            label = symbol.name,
            detail = symbol.detail,
            kind =
                when (symbol.kind) {
                    SymbolKind.MODULE -> CompletionItemKind.MODULE
                    SymbolKind.FUNCTION,
                    SymbolKind.BUILTIN_FUNCTION,
                    -> CompletionItemKind.FUNCTION
                    SymbolKind.VARIABLE -> CompletionItemKind.VARIABLE
                    SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
                    SymbolKind.RECORD,
                    SymbolKind.BUILTIN_TYPE,
                    -> CompletionItemKind.TYPE
                    SymbolKind.FIELD -> CompletionItemKind.FIELD
                },
            documentation = symbol.documentation,
        )

    private fun moduleMemberPrefix(
        source: String,
        offset: Int,
    ): Pair<String, String>? {
        val prefix = source.take(offset)
        val match = MODULE_MEMBER_REGEX.find(prefix) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun identifierPrefix(
        source: String,
        offset: Int,
    ): String = IDENTIFIER_PREFIX_REGEX.find(source.take(offset))?.value.orEmpty()

    private fun offsetAt(
        source: String,
        line: Int,
        column: Int,
    ): Int {
        var index = 0
        var currentLine = 0
        var currentColumn = 0
        while (index < source.length) {
            if (currentLine == line && currentColumn == column) {
                return index
            }
            val ch = source[index]
            index += 1
            if (ch == '\n') {
                currentLine += 1
                currentColumn = 0
            } else {
                currentColumn += 1
            }
        }
        return source.length
    }

    private companion object {
        val KEYWORDS = listOf("fun", "val", "var", "if", "else", "while", "return", "import", "struct", "true", "false", "null")
        val IDENTIFIER_PREFIX_REGEX = Regex("""[A-Za-z_][A-Za-z0-9_]*$""")
        val MODULE_MEMBER_REGEX = Regex("""([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z0-9_]*)$""")
    }
}
