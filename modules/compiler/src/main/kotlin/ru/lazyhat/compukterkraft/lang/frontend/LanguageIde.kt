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

import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItemKind
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.Diagnostic
import ru.lazyhat.compukterkraft.lang.runtime.HighlightToken
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo

class LanguageIde(
    private val frontend: LanguageFrontend = LanguageFrontend(),
) : IdeFacade {
    override fun analyze(
        name: String,
        source: String,
    ): IdeSnapshot {
        val analysis = frontend.analyze(name, source)
        return IdeSnapshot(
            diagnostics = analysis.diagnostics.map(IdePresentationSupport::diagnostic),
            highlights = IdePresentationSupport.highlights(analysis.tokens, analysis.references),
            analysis = analysis,
        )
    }

    override fun complete(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> {
        val snapshot = analyze(name, source)
        return completeFromAnalysis(snapshot.analysis, source, line, column)
    }

    override fun completeFromAnalysis(
        analysis: AnalyzedProgram,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem> {
        val offset = SourceTextSupport.offsetAt(source, line, column)
        val importPrefix = SourceTextSupport.importPrefix(source, offset)
        if (importPrefix != null) {
            val alreadyImported = analysis.importedModuleNames
            return LanguageBuiltins.registry.modules
                .asSequence()
                .filter { it.name.startsWith(importPrefix) }
                .filter { it.name !in alreadyImported }
                .map {
                    CompletionItem(
                        label = it.name,
                        detail = it.documentation,
                        kind = CompletionItemKind.MODULE,
                        documentation = it.documentation,
                    )
                }.toList()
        }
        val prefix = SourceTextSupport.identifierPrefix(source, offset)
        val modulePrefix = SourceTextSupport.moduleMemberPrefix(source, offset)
        return if (modulePrefix != null) {
            analysis
                .moduleMembers(modulePrefix.first)
                .asSequence()
                .filter { it.name.startsWith(modulePrefix.second) }
                .map(IdePresentationSupport::completionItem)
                .distinctBy { it.kind to it.label }
                .toList()
        } else {
            buildList {
                addAll(
                    analysis
                        .visibleSymbolsAt(offset)
                        .asSequence()
                        .filter { it.name.startsWith(prefix) }
                        .map(IdePresentationSupport::completionItem)
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
                                insertText = if (it in BODY_KEYWORDS) "$it " else null,
                            )
                        }.toList(),
                )
            }.distinctBy { it.kind to it.label }
        }
    }

    override fun hover(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): HoverInfo? {
        val snapshot = analyze(name, source)
        val offset = SourceTextSupport.offsetAt(source, line, column)
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

    override fun definition(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): DefinitionTarget? {
        val snapshot = analyze(name, source)
        val offset = SourceTextSupport.offsetAt(source, line, column)
        val targetRange =
            snapshot.analysis
                .referenceAt(offset)
                ?.target
                ?.range ?: return null
        return DefinitionTarget(path = name, range = targetRange)
    }

    data class IdeSnapshot(
        val diagnostics: List<Diagnostic>,
        val highlights: List<HighlightToken>,
        val analysis: AnalyzedProgram,
    )

    private companion object {
        val KEYWORDS = listOf("fun", "val", "var", "if", "else", "when", "while", "return", "import", "struct", "true", "false", "null")
        val BODY_KEYWORDS = setOf("fun", "val", "var", "if", "else", "when", "while", "return", "import", "struct")
    }
}
