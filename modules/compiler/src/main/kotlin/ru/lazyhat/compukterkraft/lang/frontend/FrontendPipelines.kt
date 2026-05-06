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

import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.FunctionDeclaration
import ru.lazyhat.compukterkraft.lang.api.ImportSource
import ru.lazyhat.compukterkraft.lang.api.Program
import ru.lazyhat.compukterkraft.lang.api.SourceRange
import ru.lazyhat.compukterkraft.lang.api.Token
import ru.lazyhat.compukterkraft.lang.api.Visibility
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo

enum class CommentKind {
    LINE,
    BLOCK,
}

data class CommentTrivia(
    val kind: CommentKind,
    val text: String,
    val range: SourceRange,
)

data class ParsedSource(
    val name: String,
    val source: String,
    val tokens: List<Token>,
    val comments: List<CommentTrivia>,
    val syntaxDiagnostics: List<FrontendDiagnostic>,
    val program: Program,
)

interface ParserFacade {
    fun parse(
        name: String,
        source: String,
    ): ParsedSource
}

interface AnalyzerFacade {
    fun analyze(
        name: String,
        source: String,
    ): AnalyzedProgram
}

interface CompilerFacade {
    fun compile(
        name: String,
        source: String,
    ): CompilationArtifact = compile(name, source, NoOpSourceLoader)

    fun compile(
        name: String,
        source: String,
        loader: SourceLoader,
    ): CompilationArtifact
}

interface IdeFacade {
    fun analyze(
        name: String,
        source: String,
    ): LanguageIde.IdeSnapshot

    fun complete(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun completeFromAnalysis(
        analysis: AnalyzedProgram,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun hover(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): HoverInfo?

    fun definition(
        name: String,
        source: String,
        line: Int,
        column: Int,
    ): DefinitionTarget?

    fun formatDocument(
        name: String,
        source: String,
    ): FormatResult

    fun cleanupDocument(
        name: String,
        source: String,
    ): FormatResult
}

internal class DefaultParserFacade(
    private val metricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
) : ParserFacade {
    override fun parse(
        name: String,
        source: String,
    ): ParsedSource {
        val started = System.nanoTime()
        val lexer =
            Lexer(source)
        val tokens = lexer.lex()
        val parser =
            Parser(tokens, lexer.diagnostics)
        val program = parser.parseProgram()
        metricsCollector.recordParse(name, sourceBytes = source.length, tokenCount = tokens.size, nanos = System.nanoTime() - started)
        return ParsedSource(
            name = name,
            source = source,
            tokens = tokens,
            comments = lexer.comments,
            syntaxDiagnostics = lexer.diagnostics + parser.diagnostics,
            program = program,
        )
    }
}

internal class DefaultAnalyzerFacade(
    private val registry: BuiltinRegistry,
    private val metricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
    private val parser: ParserFacade =
        DefaultParserFacade(metricsCollector),
) : AnalyzerFacade {
    override fun analyze(
        name: String,
        source: String,
    ): AnalyzedProgram {
        val parsed = parser.parse(name, source)
        val program = parsed.program

        val started = System.nanoTime()
        val semantic =
            SemanticAnalyzer(registry, name)
                .analyze(program)
        val analysis = AnalyzedProgram(
            name = parsed.name,
            source = parsed.source,
            tokens = parsed.tokens,
            program = program,
            diagnostics = parsed.syntaxDiagnostics + semantic.diagnostics,
            symbols = semantic.symbols,
            references = semantic.references,
            builtinModules = registry.modules,
            builtinGlobals = registry.globals,
        ).rememberSemantic(semantic)
        metricsCollector.recordAnalyze(
            name,
            diagnostics = analysis.diagnostics.size,
            symbols = analysis.symbols.size,
            references = analysis.references.size,
            nanos = System.nanoTime() - started,
        )
        return analysis
    }
}

internal class DefaultCompilerFacade(
    private val registry: BuiltinRegistry,
    private val analyzer: AnalyzerFacade,
    private val metricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
) : CompilerFacade {
    override fun compile(
        name: String,
        source: String,
        loader: SourceLoader,
    ): CompilationArtifact {
        val compileStarted = System.nanoTime()
        val project = analyzeProject(name, source, loader)
        val analysis = project.getValue(name)
        val semantic = analysis.semantic
        val sourceBytes = project.values.sumOf { it.source.length }
        val diagnostics = project.values.sumOf { candidate -> candidate.diagnostics.size }
        if (semantic == null ||
            project.values.any { candidate -> candidate.diagnostics.any { it.severity == FrontendSeverity.ERROR } }
        ) {
            metricsCollector.recordCompile(
                name,
                sourceCount = project.size,
                sourceBytes = sourceBytes,
                diagnostics = diagnostics,
                nanos = System.nanoTime() - compileStarted,
            )
            return CompilationArtifact(
                module = null,
                analysis = analysis,
                analyses = project,
                profiling = metricsCollector.snapshot(),
            )
        }

        val codegenStarted = System.nanoTime()
        val module =
            BytecodeCompiler(registry, semantic, project.values.mapNotNull { it.semantic })
                .compile(name)
        metricsCollector.recordCodegen(
            name,
            functionCount = module.functions.size,
            instructionCount = module.instructionCount(),
            nanos = System.nanoTime() - codegenStarted,
        )
        metricsCollector.recordCompile(
            name,
            sourceCount = project.size,
            sourceBytes = sourceBytes,
            diagnostics = diagnostics,
            nanos = System.nanoTime() - compileStarted,
        )
        return CompilationArtifact(
            module = module,
            analysis = analysis,
            analyses = project,
            profiling = metricsCollector.snapshot(),
        )
    }

    private fun analyzeProject(
        rootName: String,
        rootSource: String,
        loader: SourceLoader,
    ): Map<String, AnalyzedProgram> {
        val parser = DefaultParserFacade(metricsCollector)
        val parsed = linkedMapOf<String, ParsedSource>()
        val importDiagnostics = linkedMapOf<String, MutableList<FrontendDiagnostic>>()

        fun parse(
            canonical: String,
            source: String,
        ) {
            if (parsed.containsKey(canonical)) return
            val current = parser.parse(canonical, source)
            parsed[canonical] = current
            val diagnostics = mutableListOf<FrontendDiagnostic>()
            importDiagnostics[canonical] = diagnostics
            current.program.imports.forEach { declaration ->
                val source = declaration.source as? ImportSource.FilePath ?: return@forEach
                val resolved = loader.resolve(canonical, source.path)
                if (resolved == null) {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Cannot resolve import `${source.path}`.",
                            source.range,
                        )
                    return@forEach
                }
                if (parsed.containsKey(resolved)) return@forEach
                val importedSource = loader.read(resolved)
                if (importedSource == null) {
                    diagnostics +=
                        FrontendDiagnostic(
                            "Failed to read source `${source.path}` (resolved to `$resolved`).",
                            source.range,
                        )
                    return@forEach
                }
                parse(resolved, importedSource)
            }
        }

        parse(rootName, rootSource)

        val exports = parsed.mapValues { (canonical, source) -> ModuleExports(canonical, source.program) }
        return parsed.mapValues { (canonical, source) ->
            val started = System.nanoTime()
            val semantic =
                SemanticAnalyzer(
                    registry = registry,
                    sourceName = canonical,
                    resolveImport = { path -> loader.resolve(canonical, path) },
                    lookupExports = { dependency -> exports[dependency] },
                ).analyze(source.program)
            val analysis = AnalyzedProgram(
                name = source.name,
                source = source.source,
                tokens = source.tokens,
                program = source.program,
                diagnostics =
                    source.syntaxDiagnostics +
                        (importDiagnostics[canonical] ?: emptyList()) +
                        semantic.diagnostics +
                        if (canonical == rootName) entryPointDiagnostics(source) else emptyList(),
                symbols = semantic.symbols,
                references = semantic.references,
                builtinModules = registry.modules,
                builtinGlobals = registry.globals,
            ).rememberSemantic(semantic)
            metricsCollector.recordAnalyze(
                canonical,
                diagnostics = analysis.diagnostics.size,
                symbols = analysis.symbols.size,
                references = analysis.references.size,
                nanos = System.nanoTime() - started,
            )
            analysis
        }
    }

    private fun entryPointDiagnostics(source: ParsedSource): List<FrontendDiagnostic> {
        val main =
            source.program.declarations
                .filterIsInstance<FunctionDeclaration>()
                .firstOrNull { it.name == "main" }
        return when {
            main == null -> {
                listOf(
                    FrontendDiagnostic(
                        "Program must declare `pub fun main()`.",
                        source.program.range ?: source.tokens.last().range,
                    ),
                )
            }

            main.visibility != Visibility.PUBLIC -> {
                listOf(FrontendDiagnostic("Entry point `main` must be declared as `pub fun main()`.", main.range))
            }

            else -> {
                emptyList()
            }
        }
    }
}

private fun BytecodeModule.instructionCount(): Int = functions.sumOf { it.instructions.size }
