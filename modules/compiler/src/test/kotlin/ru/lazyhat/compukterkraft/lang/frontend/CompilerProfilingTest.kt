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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompilerProfilingTest {
    @Test
    fun recordingCollectorAccumulatesCompilerPhases() {
        val collector = RecordingCompilerMetricsCollector()

        collector.recordParse("main.ck", sourceBytes = 24, tokenCount = 7, nanos = 10)
        collector.recordAnalyze("main.ck", diagnostics = 0, symbols = 1, references = 0, nanos = 20)
        collector.recordCodegen("main.ck", functionCount = 1, instructionCount = 2, nanos = 30)
        collector.recordCompile("main.ck", sourceCount = 1, sourceBytes = 24, diagnostics = 0, nanos = 60)

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.parseCalls)
        assertEquals(10, snapshot.parseNanos)
        assertEquals(7, snapshot.tokens)
        assertEquals(1, snapshot.analyzeCalls)
        assertEquals(20, snapshot.analyzeNanos)
        assertEquals(1, snapshot.symbols)
        assertEquals(1, snapshot.codegenCalls)
        assertEquals(30, snapshot.codegenNanos)
        assertEquals(2, snapshot.instructions)
        assertEquals(1, snapshot.compileCalls)
        assertEquals(60, snapshot.compileNanos)
        assertEquals(60, snapshot.averageCompileNanos)
        val summary = snapshot.summary()
        assertTrue(summary.startsWith("compiler:\n"), summary)
        assertTrue(summary.contains("  totals: calls=1, time=60 ns, avg=60 ns, sources=1, sourceBytes=24 B, diagnostics=0"), summary)
        assertTrue(summary.contains("  phases:\n"), summary)
        assertTrue(summary.contains("    parse: calls=1, time=10 ns, avg=10 ns, tokens=7"), summary)
    }

    @Test
    fun languageFrontendRecordsCompileMetrics() {
        val collector = RecordingCompilerMetricsCollector()
        val artifact = LanguageFrontend(compilerMetricsCollector = collector).compile("main.ck", "pub fun main() {}")

        val snapshot = collector.snapshot()

        assertNotNull(artifact.module)
        assertTrue(snapshot.parseCalls >= 1, snapshot.summary())
        assertTrue(snapshot.analyzeCalls >= 1, snapshot.summary())
        assertTrue(snapshot.codegenCalls >= 1, snapshot.summary())
        assertEquals(1, snapshot.compileCalls)
        assertTrue(snapshot.compileNanos > 0, snapshot.summary())
        assertTrue(snapshot.instructions > 0, snapshot.summary())
        assertEquals(snapshot, artifact.profiling)
    }
}
