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
package ck.lang.frontend

import ck.lang.api.BuiltinRegistry
import ck.lang.api.Program
import ck.lang.api.Token
import ck.lang.runtime.CompletionItem
import ck.lang.runtime.DefinitionTarget
import ck.lang.runtime.HoverInfo

data class ParsedSource(
    val name: String,
    val source: String,
    val tokens: List<Token>,
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
}

internal class DefaultParserFacade : ParserFacade {
    override fun parse(
        name: String,
        source: String,
    ): ParsedSource {
        val lexer = Lexer(source)
        val tokens = lexer.lex()
        val parser = Parser(tokens, lexer.diagnostics)
        val program = parser.parseProgram()
        return ParsedSource(
            name = name,
            source = source,
            tokens = tokens,
            syntaxDiagnostics = lexer.diagnostics + parser.diagnostics,
            program = program,
        )
    }
}

internal class DefaultAnalyzerFacade(
    private val registry: BuiltinRegistry,
    private val parser: ParserFacade = DefaultParserFacade(),
) : AnalyzerFacade {
    override fun analyze(
        name: String,
        source: String,
    ): AnalyzedProgram {
        val parsed = parser.parse(name, source)
        val program = parsed.program

        val semantic = SemanticAnalyzer(registry, name).analyze(program)
        return AnalyzedProgram(
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
    }
}

internal class DefaultCompilerFacade(
    private val registry: BuiltinRegistry,
    private val analyzer: AnalyzerFacade,
) : CompilerFacade {
    override fun compile(
        name: String,
        source: String,
    ): CompilationArtifact {
        val analysis = analyzer.analyze(name, source)
        val semantic = analysis.semantic
        if (semantic == null ||
            analysis.diagnostics.any { it.severity == FrontendSeverity.ERROR }
        ) {
            return CompilationArtifact(module = null, analysis = analysis)
        }

        return CompilationArtifact(
            module = BytecodeCompiler(registry, semantic).compile(name),
            analysis = analysis,
        )
    }
}
