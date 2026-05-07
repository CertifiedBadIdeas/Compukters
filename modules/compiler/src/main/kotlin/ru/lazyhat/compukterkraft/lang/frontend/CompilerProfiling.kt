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

import java.util.concurrent.atomic.AtomicLong

interface CompilerMetricsCollector {
    fun recordParse(
        sourceName: String,
        sourceBytes: Int,
        tokenCount: Int,
        nanos: Long,
    )

    fun recordAnalyze(
        sourceName: String,
        diagnostics: Int,
        symbols: Int,
        references: Int,
        nanos: Long,
    )

    fun recordCodegen(
        sourceName: String,
        functionCount: Int,
        instructionCount: Int,
        nanos: Long,
    )

    fun recordCompile(
        rootName: String,
        sourceCount: Int,
        sourceBytes: Int,
        diagnostics: Int,
        nanos: Long,
    )

    fun snapshot(): CompilerProfilingSnapshot
}

data class CompilerProfilingSnapshot(
    val parseCalls: Long = 0,
    val parseNanos: Long = 0,
    val sourceBytes: Long = 0,
    val tokens: Long = 0,
    val analyzeCalls: Long = 0,
    val analyzeNanos: Long = 0,
    val diagnostics: Long = 0,
    val symbols: Long = 0,
    val references: Long = 0,
    val codegenCalls: Long = 0,
    val codegenNanos: Long = 0,
    val functions: Long = 0,
    val instructions: Long = 0,
    val compileCalls: Long = 0,
    val compileNanos: Long = 0,
    val compiledSources: Long = 0,
) {
    val averageParseNanos: Long get() = average(parseNanos, parseCalls)
    val averageAnalyzeNanos: Long get() = average(analyzeNanos, analyzeCalls)
    val averageCodegenNanos: Long get() = average(codegenNanos, codegenCalls)
    val averageCompileNanos: Long get() = average(compileNanos, compileCalls)

    fun summary(): String =
        buildString {
            appendLine("compiler:")
            appendLine("  totals: calls=$compileCalls, time=${compileNanos.nanos()}, avg=${averageCompileNanos.nanos()}, sources=$compiledSources, sourceBytes=${sourceBytes.bytes()}, diagnostics=$diagnostics")
            appendLine("  phases:")
            appendLine("    parse: calls=$parseCalls, time=${parseNanos.nanos()}, avg=${averageParseNanos.nanos()}, tokens=$tokens")
            appendLine("    analyze: calls=$analyzeCalls, time=${analyzeNanos.nanos()}, avg=${averageAnalyzeNanos.nanos()}, symbols=$symbols, references=$references")
            append("    codegen: calls=$codegenCalls, time=${codegenNanos.nanos()}, avg=${averageCodegenNanos.nanos()}, functions=$functions, instructions=$instructions")
        }
}

object NoOpCompilerMetricsCollector : CompilerMetricsCollector {
    override fun recordParse(
        sourceName: String,
        sourceBytes: Int,
        tokenCount: Int,
        nanos: Long,
    ) = Unit

    override fun recordAnalyze(
        sourceName: String,
        diagnostics: Int,
        symbols: Int,
        references: Int,
        nanos: Long,
    ) = Unit

    override fun recordCodegen(
        sourceName: String,
        functionCount: Int,
        instructionCount: Int,
        nanos: Long,
    ) = Unit

    override fun recordCompile(
        rootName: String,
        sourceCount: Int,
        sourceBytes: Int,
        diagnostics: Int,
        nanos: Long,
    ) = Unit

    override fun snapshot(): CompilerProfilingSnapshot = CompilerProfilingSnapshot()
}

class RecordingCompilerMetricsCollector : CompilerMetricsCollector {
    private val parseCalls = AtomicLong()
    private val parseNanos = AtomicLong()
    private val sourceBytes = AtomicLong()
    private val tokens = AtomicLong()
    private val analyzeCalls = AtomicLong()
    private val analyzeNanos = AtomicLong()
    private val diagnostics = AtomicLong()
    private val symbols = AtomicLong()
    private val references = AtomicLong()
    private val codegenCalls = AtomicLong()
    private val codegenNanos = AtomicLong()
    private val functions = AtomicLong()
    private val instructions = AtomicLong()
    private val compileCalls = AtomicLong()
    private val compileNanos = AtomicLong()
    private val compiledSources = AtomicLong()

    override fun recordParse(
        sourceName: String,
        sourceBytes: Int,
        tokenCount: Int,
        nanos: Long,
    ) {
        parseCalls.incrementAndGet()
        parseNanos.addAndGet(nanos.coerceAtLeast(0))
        this.sourceBytes.addAndGet(sourceBytes.coerceAtLeast(0).toLong())
        tokens.addAndGet(tokenCount.coerceAtLeast(0).toLong())
    }

    override fun recordAnalyze(
        sourceName: String,
        diagnostics: Int,
        symbols: Int,
        references: Int,
        nanos: Long,
    ) {
        analyzeCalls.incrementAndGet()
        analyzeNanos.addAndGet(nanos.coerceAtLeast(0))
        this.diagnostics.addAndGet(diagnostics.coerceAtLeast(0).toLong())
        this.symbols.addAndGet(symbols.coerceAtLeast(0).toLong())
        this.references.addAndGet(references.coerceAtLeast(0).toLong())
    }

    override fun recordCodegen(
        sourceName: String,
        functionCount: Int,
        instructionCount: Int,
        nanos: Long,
    ) {
        codegenCalls.incrementAndGet()
        codegenNanos.addAndGet(nanos.coerceAtLeast(0))
        functions.addAndGet(functionCount.coerceAtLeast(0).toLong())
        instructions.addAndGet(instructionCount.coerceAtLeast(0).toLong())
    }

    override fun recordCompile(
        rootName: String,
        sourceCount: Int,
        sourceBytes: Int,
        diagnostics: Int,
        nanos: Long,
    ) {
        compileCalls.incrementAndGet()
        compileNanos.addAndGet(nanos.coerceAtLeast(0))
        compiledSources.addAndGet(sourceCount.coerceAtLeast(0).toLong())
        this.diagnostics.addAndGet(diagnostics.coerceAtLeast(0).toLong())
    }

    override fun snapshot(): CompilerProfilingSnapshot =
        CompilerProfilingSnapshot(
            parseCalls = parseCalls.get(),
            parseNanos = parseNanos.get(),
            sourceBytes = sourceBytes.get(),
            tokens = tokens.get(),
            analyzeCalls = analyzeCalls.get(),
            analyzeNanos = analyzeNanos.get(),
            diagnostics = diagnostics.get(),
            symbols = symbols.get(),
            references = references.get(),
            codegenCalls = codegenCalls.get(),
            codegenNanos = codegenNanos.get(),
            functions = functions.get(),
            instructions = instructions.get(),
            compileCalls = compileCalls.get(),
            compileNanos = compileNanos.get(),
            compiledSources = compiledSources.get(),
        )
}

private fun average(
    total: Long,
    count: Long,
): Long = if (count <= 0) 0 else total / count

private fun Long.nanos(): String = "$this ns"

private fun Long.bytes(): String = "$this B"
