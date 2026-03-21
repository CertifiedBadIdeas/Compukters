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

package ru.lazyhat.compuktercraft.scripting.impl

import ru.lazyhat.compuktercraft.scripting.api.CompletionItem
import ru.lazyhat.compuktercraft.scripting.api.CompletionItemKind
import ru.lazyhat.compuktercraft.scripting.api.DefinitionTarget
import ru.lazyhat.compuktercraft.scripting.api.Diagnostic
import ru.lazyhat.compuktercraft.scripting.api.HighlightToken
import ru.lazyhat.compuktercraft.scripting.api.HighlightTokenType
import ru.lazyhat.compuktercraft.scripting.api.HoverInfo
import ru.lazyhat.compuktercraft.scripting.api.ScriptIdeService

class ScriptIdeServiceImpl(
    private val environment: ScriptingEnvironmentImpl,
) : ScriptIdeService {
    override fun analyze(
        name: String,
        code: String,
    ): List<Diagnostic> = environment.compiler.compile(name, code).diagnostics

    override fun highlight(
        name: String,
        code: String,
    ): List<HighlightToken> {
        val document = TextDocument(code)

        return buildList {
            addAll(document.highlightMatches(STRING_REGEX, HighlightTokenType.STRING))
            addAll(document.highlightMatches(COMMENT_REGEX, HighlightTokenType.COMMENT))
            addAll(document.highlightMatches(NUMBER_REGEX, HighlightTokenType.NUMBER))
            addAll(document.highlightMatches(KEYWORD_REGEX, HighlightTokenType.KEYWORD))
        }.sortedBy { it.range.start.line * 10_000 + it.range.start.column }
    }

    override fun complete(
        name: String,
        code: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> {
        val document = TextDocument(code)
        val prefix = document.prefixAt(line, column)
        val importedSymbols =
            environment.defaultImports.map { importPath ->
                val symbol = importPath.substringAfterLast('.').removeSuffix("*")
                CompletionItem(
                    label = symbol,
                    insertText = symbol,
                    detail = importPath,
                    kind = CompletionItemKind.IMPORT,
                )
            }
        val declaredSymbols =
            document.declarations().map {
                CompletionItem(
                    label = it.name,
                    insertText = it.name,
                    detail = "Declared in current script",
                    kind = it.kind,
                )
            }
        val keywordItems =
            KEYWORDS.map {
                CompletionItem(
                    label = it,
                    insertText = it,
                    detail = "Kotlin keyword",
                    kind = CompletionItemKind.KEYWORD,
                )
            }

        return (keywordItems + importedSymbols + declaredSymbols)
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
        val document = TextDocument(code)
        val selection = document.wordAt(line, column) ?: return null
        val keywordHover = KEYWORD_HOVERS[selection.word]
        if (keywordHover != null) {
            return HoverInfo(keywordHover, selection.range)
        }

        val declaration = document.declarations().firstOrNull { it.name == selection.word }
        if (declaration != null) {
            return HoverInfo("Declared in current script as `${declaration.name}`", declaration.range)
        }

        val imported = environment.defaultImports.firstOrNull { it.endsWith(selection.word) || it.endsWith("${selection.word}.*") }
        return imported?.let { HoverInfo("Imported from `$it`", selection.range) }
    }

    override fun definition(
        name: String,
        code: String,
        line: Int,
        column: Int,
    ): DefinitionTarget? {
        val document = TextDocument(code)
        val selection = document.wordAt(line, column) ?: return null
        return document.definition(name, selection.word)
    }

    private companion object {
        val KEYWORDS =
            listOf(
                "class",
                "object",
                "fun",
                "val",
                "var",
                "if",
                "else",
                "when",
                "for",
                "while",
                "return",
                "true",
                "false",
                "null",
                "import",
                "package",
            )

        val KEYWORD_HOVERS =
            mapOf(
                "class" to "Declares a Kotlin class.",
                "object" to "Declares a Kotlin singleton object.",
                "fun" to "Declares a Kotlin function.",
                "val" to "Declares an immutable value.",
                "var" to "Declares a mutable variable.",
                "when" to "Matches and branches on expressions.",
                "import" to "Brings symbols from another package into scope.",
            )

        val COMMENT_REGEX = Regex("""//.*|/\*[\s\S]*?\*/""")
        val STRING_REGEX = Regex(""""([^"\\\\]|\\\\.)*"|'([^'\\\\]|\\\\.)*'""")
        val NUMBER_REGEX = Regex("""\b\d+(\.\d+)?\b""")
        val KEYWORD_REGEX = Regex("""\b(${KEYWORDS.joinToString("|")})\b""")
    }
}
