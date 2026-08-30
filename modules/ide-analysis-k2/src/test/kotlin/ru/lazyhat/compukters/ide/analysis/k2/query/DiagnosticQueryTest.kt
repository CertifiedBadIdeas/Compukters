/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.ide.analysis.k2.query

import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosticQueryTest {
    @Test
    fun `core guest API resolves redstone facade without errors`() {
        val source =
            """
            import compukter.redstone.Redstone

            fun main() {
                val outputs = Redstone.outputs()
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(AnalysisQuery.Presentation(fixture.identity)) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(
                active.diagnostics.none { it.severity == EditorDiagnosticSeverity.Error },
                active.diagnostics.toString(),
            )
        }
    }

    @Test
    fun `type error after supplementary character keeps UTF-16 range`() {
        val source = "val emoji = \"😀\"\nval answer: String = 42"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.execute(AnalysisQuery.Presentation(fixture.identity)) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
            val offset = source.lastIndexOf('=')

            assertTrue(
                active.diagnostics.any {
                    it.severity == EditorDiagnosticSeverity.Error && it.range == EditorRange(offset, offset + 1)
                },
                active.diagnostics.toString(),
            )
        }
    }

    @Test
    fun `diagnostics from multiple files retain their virtual paths`() {
        K2QueryFixture
            .source(
                "a.kt" to "val first: String = 1",
                "nested/b.kt" to "val second: Int = \"bad\"",
            ).use { fixture ->
                val result = fixture.execute(AnalysisQuery.Presentation(fixture.identity)) as AnalysisResult.Presentation
                val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

                assertEquals(setOf("a.kt", "nested/b.kt"), active.diagnostics.mapNotNull { it.path?.value }.toSet())
            }
    }

    @Test
    fun `incomplete syntax produces a bounded diagnostic instead of failing analysis`() {
        K2QueryFixture.source("main.kt" to "fun main( {").use { fixture ->
            val result = fixture.execute(AnalysisQuery.Presentation(fixture.identity)) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            assertTrue(active.diagnostics.isNotEmpty())
        }
    }

    @Test
    fun `raw diagnostics beyond the negotiated cap fail explicitly`() {
        K2QueryFixture.source("main.kt" to "val broken: String = 42").use { fixture ->
            assertFailsWith<AnalysisOutputLimitException> {
                fixture.execute(AnalysisQuery.Presentation(fixture.identity), AnalysisLimits(diagnostics = 0))
            }
        }
    }
}
