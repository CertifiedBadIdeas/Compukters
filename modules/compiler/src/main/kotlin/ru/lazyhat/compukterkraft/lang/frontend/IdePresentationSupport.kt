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

import ru.lazyhat.compukterkraft.lang.api.Token
import ru.lazyhat.compukterkraft.lang.api.TokenKind
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItemKind
import ru.lazyhat.compukterkraft.lang.runtime.Diagnostic
import ru.lazyhat.compukterkraft.lang.runtime.HighlightToken
import ru.lazyhat.compukterkraft.lang.runtime.HighlightTokenKind
import ru.lazyhat.compukterkraft.lang.runtime.IdeDiagnosticSeverity

internal object IdePresentationSupport {
    fun diagnostic(diagnostic: FrontendDiagnostic): Diagnostic =
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

    fun highlights(
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

    fun completionItem(symbol: SymbolInfo): CompletionItem {
        val kind =
            when (symbol.kind) {
                SymbolKind.MODULE -> CompletionItemKind.MODULE
                SymbolKind.FUNCTION, SymbolKind.BUILTIN_FUNCTION -> CompletionItemKind.FUNCTION
                SymbolKind.VARIABLE -> CompletionItemKind.VARIABLE
                SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
                SymbolKind.RECORD, SymbolKind.BUILTIN_TYPE -> CompletionItemKind.TYPE
                SymbolKind.FIELD -> CompletionItemKind.FIELD
            }
        val (insertText, cursorOffset) =
            if (kind == CompletionItemKind.FUNCTION) {
                "${symbol.name}()" to symbol.name.length + 1
            } else {
                null to null
            }
        return CompletionItem(
            label = symbol.name,
            detail = symbol.detail,
            kind = kind,
            documentation = symbol.documentation,
            insertText = insertText,
            cursorOffset = cursorOffset,
        )
    }

    private fun highlightKindForSymbol(kind: SymbolKind): HighlightTokenKind =
        when (kind) {
            SymbolKind.MODULE -> HighlightTokenKind.MODULE
            SymbolKind.FUNCTION, SymbolKind.BUILTIN_FUNCTION -> HighlightTokenKind.FUNCTION
            SymbolKind.RECORD, SymbolKind.BUILTIN_TYPE -> HighlightTokenKind.TYPE
            SymbolKind.FIELD -> HighlightTokenKind.FIELD
            SymbolKind.VARIABLE, SymbolKind.PARAMETER -> HighlightTokenKind.IDENTIFIER
        }

    private fun highlightKindForToken(kind: TokenKind): HighlightTokenKind? =
        when (kind) {
            TokenKind.TRUE, TokenKind.FALSE -> HighlightTokenKind.BOOLEAN

            TokenKind.NULL -> HighlightTokenKind.NULL

            TokenKind.NUMBER -> HighlightTokenKind.NUMBER

            TokenKind.STRING -> HighlightTokenKind.STRING

            TokenKind.FUN,
            TokenKind.VAL,
            TokenKind.VAR,
            TokenKind.IF,
            TokenKind.ELSE,
            TokenKind.WHILE,
            TokenKind.WHEN,
            TokenKind.RETURN,
            TokenKind.IMPORT,
            TokenKind.AS,
            TokenKind.STRUCT,
            TokenKind.CLASS,
            TokenKind.STATIC,
            TokenKind.INIT,
            TokenKind.THIS,
            -> HighlightTokenKind.KEYWORD

            TokenKind.PLUS,
            TokenKind.MINUS,
            TokenKind.STAR,
            TokenKind.SLASH,
            TokenKind.BANG,
            TokenKind.EQUAL,
            TokenKind.PLUS_EQUAL,
            TokenKind.MINUS_EQUAL,
            TokenKind.STAR_EQUAL,
            TokenKind.SLASH_EQUAL,
            TokenKind.EQUAL_EQUAL,
            TokenKind.BANG_EQUAL,
            TokenKind.LT,
            TokenKind.LTE,
            TokenKind.GT,
            TokenKind.GTE,
            TokenKind.AMP_AMP,
            TokenKind.PIPE_PIPE,
            TokenKind.ARROW,
            -> HighlightTokenKind.OPERATOR

            TokenKind.COLON,
            TokenKind.COLON_COLON,
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
}
