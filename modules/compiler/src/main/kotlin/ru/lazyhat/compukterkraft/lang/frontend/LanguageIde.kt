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

import ru.lazyhat.compukterkraft.lang.api.ClassDeclaration
import ru.lazyhat.compukterkraft.lang.api.FunctionDeclaration
import ru.lazyhat.compukterkraft.lang.api.StructDeclaration
import ru.lazyhat.compukterkraft.lang.api.Visibility
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItemKind
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.Diagnostic
import ru.lazyhat.compukterkraft.lang.runtime.HighlightToken
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo

class LanguageIde(
    private val frontend: LanguageFrontend = LanguageFrontend(),
    private val registry: ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry = frontend.registry,
    private val parser: ParserFacade = DefaultParserFacade(),
    private val sourceIndex: SourceIndex = EmptySourceIndex,
    private val formatter: LanguageFormatter = LanguageFormatter(parser),
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
            return emptyList()
        }
        val prefix = SourceTextSupport.identifierPrefix(source, offset)
        val modulePrefix = SourceTextSupport.moduleMemberPrefix(source, offset)
        return if (modulePrefix != null) {
            buildList {
                addAll(analysis.moduleMembers(modulePrefix.first))
                addAll(classMemberSymbols(analysis, source, offset, modulePrefix.first))
            }.asSequence()
                .filter { it.name.startsWith(modulePrefix.second) }
                .map(IdePresentationSupport::completionItem)
                .distinctBy { it.kind to it.label }
                .toList()
        } else {
            val visibleSymbols = analysis.visibleSymbolsAt(offset)
            val hiddenNames = visibleSymbols.map { it.name }.toSet()
            buildList {
                addAll(
                    visibleSymbols
                        .asSequence()
                        .filter { it.name.startsWith(prefix) }
                        .map(IdePresentationSupport::completionItem)
                        .toList(),
                )
                addAll(builtinImportableCompletions(source, prefix, hiddenNames))
                addAll(userFileImportableCompletions(analysis.name, source, prefix, hiddenNames))
                addAll(
                    registry.builtinTypes
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

    private fun builtinImportableCompletions(
        source: String,
        prefix: String,
        hiddenNames: Set<String>,
    ): List<CompletionItem> =
        registry.modules
            .asSequence()
            .flatMap { module ->
                module.functions.asSequence().map { function -> module to function }
            }.filter { (_, function) ->
                function.name.startsWith(prefix) && function.name !in hiddenNames
            }.map { (module, function) ->
                CompletionItem(
                    label = function.name,
                    detail = "${module.name}::${function.name}(${function.parameterTypes.joinToString()}): ${function.returnType}",
                    kind = CompletionItemKind.FUNCTION,
                    documentation = function.documentation,
                    insertText = "${function.name}()",
                    cursorOffset = "${function.name}(".length,
                    sourceNamespace = module.name,
                    additionalTextEdits =
                        listOf(
                            SourceTextSupport.importGroupEdit(
                                ImportGroupEditRequest(
                                    source,
                                    module.name,
                                    function.name,
                                ),
                            ),
                        ),
                )
            }.toList()

    private fun userFileImportableCompletions(
        currentPath: String,
        source: String,
        prefix: String,
        hiddenNames: Set<String>,
    ): List<CompletionItem> =
        sourceIndex
            .listSources()
            .asSequence()
            .filter { it != currentPath }
            .flatMap { path ->
                val indexedSource = sourceIndex.readIndexedSource(path) ?: return@flatMap emptySequence()
                val parsed = parser.parse(path, indexedSource)
                parsed.program.declarations.asSequence().mapNotNull { declaration ->
                    val name =
                        when (declaration) {
                            is ClassDeclaration -> declaration.name
                            is FunctionDeclaration -> declaration.name
                            is StructDeclaration -> declaration.name
                        }
                    val visibility =
                        when (declaration) {
                            is ClassDeclaration -> declaration.visibility
                            is FunctionDeclaration -> declaration.visibility
                            is StructDeclaration -> declaration.visibility
                        }
                    if (visibility != Visibility.PUBLIC) return@mapNotNull null
                    if (!name.startsWith(prefix) || name in hiddenNames) return@mapNotNull null
                    CompletionItem(
                        label = name,
                        detail =
                            when (declaration) {
                                is ClassDeclaration -> "class $name"
                                is FunctionDeclaration -> "fun $name"
                                is StructDeclaration -> "struct $name"
                            },
                        kind =
                            when (declaration) {
                                is ClassDeclaration -> CompletionItemKind.TYPE
                                is FunctionDeclaration -> CompletionItemKind.FUNCTION
                                is StructDeclaration -> CompletionItemKind.TYPE
                            },
                        insertText =
                            when (declaration) {
                                is ClassDeclaration -> "$name("
                                is FunctionDeclaration -> "$name()"
                                is StructDeclaration -> null
                            },
                        cursorOffset = if (declaration is FunctionDeclaration) "$name(".length else null,
                        sourceNamespace = path,
                        additionalTextEdits = listOf(SourceTextSupport.importGroupEdit(ImportGroupEditRequest(source, "\"$path\"", name))),
                    )
                }
            }.toList()

    private fun classMemberSymbols(
        analysis: AnalyzedProgram,
        source: String,
        offset: Int,
        receiverName: String,
    ): List<SymbolInfo> {
        val declaredType = declaredReceiverType(source, offset, receiverName)
        val semantic = analysis.semantic ?: return incompleteThisMemberSymbols(source, offset, receiverName) + collectionMemberSymbols(declaredType)
        val visibleSymbols = analysis.visibleSymbolsAt(offset)
        val receiverSymbolType =
            if (receiverName == "this") {
                null
            } else {
                visibleSymbols
                    .firstOrNull { it.name == receiverName && (it.kind == SymbolKind.VARIABLE || it.kind == SymbolKind.PARAMETER) }
                    ?.detail
                    ?.substringAfter(':', "")
                    ?.trim()
                    ?.removeSuffix("?")
            }
        val receiverType = receiverSymbolType ?: declaredType
        val classBinding =
            if (receiverName == "this") {
                semantic.classBindings.values.firstOrNull { it.declaration.range.contains(offset) }
                    ?: semantic.classBindings.values
                        .filter { it.declaration.range.start.offset <= offset }
                        .maxByOrNull { it.declaration.range.start.offset }
            } else {
                visibleSymbols.firstOrNull { it.name == receiverName && it.kind == SymbolKind.CLASS }?.let { classSymbol ->
                    semantic.classBindings.values.firstOrNull { it.symbol.name == classSymbol.name }
                } ?: receiverSymbolType?.let { receiverType ->
                    semantic.classBindings.values.firstOrNull { it.symbol.name == receiverType.substringBefore('<') }
                } ?: declaredType?.let { receiverType ->
                    semantic.classBindings.values.firstOrNull { it.symbol.name == receiverType.substringBefore('<') }
                }
            } ?: return incompleteThisMemberSymbols(source, offset, receiverName) + collectionMemberSymbols(receiverType)
        val staticReceiver = visibleSymbols.any { it.name == receiverName && it.kind == SymbolKind.CLASS }
        val canSeePrivate = receiverName == "this" || classBinding.declaration.range.contains(offset)
        return if (receiverName == "this" || !staticReceiver) {
            classBinding.fields.values
                .filter { canSeePrivate || it.visibility == Visibility.PUBLIC }
                .map { it.symbol } +
                classBinding.instanceMethods.values
                    .filter { canSeePrivate || it.visibility == Visibility.PUBLIC }
                    .map { it.symbol }
        } else {
            classBinding.staticMethods.values
                .filter { canSeePrivate || it.visibility == Visibility.PUBLIC }
                .map { it.symbol }
        }
    }

    private fun collectionMemberSymbols(receiverType: String?): List<SymbolInfo> {
        val type = receiverType?.trim()?.removeSuffix("?") ?: return emptyList()
        val methods =
            when {
                type.startsWith("Array<") -> listOf("size", "get", "set", "getOrNull")
                type.startsWith("List<") -> listOf("size", "isEmpty", "get", "set", "getOrNull", "add", "insert", "removeAt", "clear")
                type.startsWith("Map<") -> listOf("size", "isEmpty", "containsKey", "get", "getOrDefault", "set", "remove", "clear", "keys", "values")
                else -> return emptyList()
            }
        return methods.map { method ->
            SymbolInfo(
                name = method,
                kind = SymbolKind.METHOD,
                range = null,
                detail = "$type.$method(...)",
            )
        }
    }

    private fun incompleteThisMemberSymbols(
        source: String,
        offset: Int,
        receiverName: String,
    ): List<SymbolInfo> {
        if (receiverName != "this") return emptyList()
        val classHeader =
            Regex("class\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)").findAll(source.take(offset)).lastOrNull()
                ?: return emptyList()
        val className = classHeader.groupValues[1]
        val parameters = classHeader.groupValues[2]
        val constructorFields =
            parameters
                .split(',')
                .mapNotNull { parameter ->
                    val match =
                        Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([^,]+)").find(parameter.trim())
                            ?: return@mapNotNull null
                    val fieldName = match.groupValues[1]
                    val fieldType = match.groupValues[2].trim()
                    SymbolInfo(
                        name = fieldName,
                        kind = SymbolKind.FIELD,
                        range = null,
                        detail = "$className.$fieldName: $fieldType",
                    )
                }
        val methods =
            Regex("\\b(?:pub\\s+)?(?:static\\s+)?fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
                .findAll(source.take(offset))
                .map { match ->
                    SymbolInfo(
                        name = match.groupValues[1],
                        kind = SymbolKind.METHOD,
                        range = null,
                        detail = "fun $className.${match.groupValues[1]}(...)",
                    )
                }.toList()
        return constructorFields + methods
    }

    private fun declaredReceiverType(
        source: String,
        offset: Int,
        receiverName: String,
    ): String? {
        val escapedName = Regex.escape(receiverName)
        val match = Regex("\\b(?:val|var)\\s+$escapedName\\s*:\\s*([^=;\\n]+)").findAll(source.take(offset)).lastOrNull()
        return match?.groupValues?.get(1)?.trim()?.removeSuffix("?")
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

    override fun formatDocument(
        name: String,
        source: String,
    ): FormatResult = formatter.formatDocument(name, source)

    override fun cleanupDocument(
        name: String,
        source: String,
    ): FormatResult = formatter.cleanupDocument(name, source, sourceIndex as? SourceLoader ?: NoOpSourceLoader)

    data class IdeSnapshot(
        val diagnostics: List<Diagnostic>,
        val highlights: List<HighlightToken>,
        val analysis: AnalyzedProgram,
    )

    private companion object {
        val KEYWORDS =
            listOf(
                "fun",
                "pub",
                "val",
                "var",
                "if",
                "else",
                "when",
                "while",
                "return",
                "import",
                "struct",
                "class",
                "true",
                "false",
                "null",
            )
        val BODY_KEYWORDS =
            setOf(
                "fun",
                "pub",
                "val",
                "var",
                "if",
                "else",
                "when",
                "while",
                "return",
                "import",
                "struct",
                "class",
            )
    }
}
