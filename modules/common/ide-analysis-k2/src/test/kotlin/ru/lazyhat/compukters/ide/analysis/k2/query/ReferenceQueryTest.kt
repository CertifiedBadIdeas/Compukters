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

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReferenceQueryTest {
    @Test
    fun `references do not mix shadowed locals with the same name`() {
        val source = "fun main() { val item = 1; println(item); run { val item = 2; println(item) }; println(item) }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.References(fixture.identity, VirtualSourcePath.kotlin("main.kt"), source.indexOf("item") + 1),
                ) as AnalysisResult.References
            assertEquals(
                listOf(
                    sourceLocation("main.kt", source.indexOf("item)"), "item"),
                    sourceLocation("main.kt", source.lastIndexOf("item)"), "item"),
                ),
                result.locations,
            )
        }
    }

    @Test
    fun `references distinguish overloads locals methods and string interpolation`() {
        val source =
            """
            fun target(value: Int) = value
            fun target(value: String) = value
            fun main() {
                target(1)
                target("text")
                val target = 3
                val text = "${'$'}target ${'$'}{target} target"
                val raw = """ + "\"\"\"${'$'}target ${'$'}{target} target\"\"\"" +
                """
                    println(target)
                }
                """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            fun references(offset: Int): List<DeclarationLocation> =
                (
                    fixture.execute(AnalysisQuery.References(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset))
                        as AnalysisResult.References
                ).locations
            assertEquals(listOf(sourceLocation("main.kt", source.indexOf("target(1)"), "target")), references(source.indexOf("target") + 1))
            assertEquals(
                listOf(sourceLocation("main.kt", source.indexOf("target(\"text\")"), "target")),
                references(source.indexOf("target(value: String)") + 1),
            )
            val localUsages =
                listOf("${'$'}target", "${'$'}{target}").flatMap { marker ->
                    generateSequence(source.indexOf(marker)) { start -> source.indexOf(marker, start + marker.length).takeIf { it >= 0 } }
                        .map { start -> sourceLocation("main.kt", start + marker.indexOf("target"), "target") }
                        .toList()
                } + sourceLocation("main.kt", source.indexOf("target)"), "target")
            assertEquals(
                localUsages.sortedBy { (it as DeclarationLocation.Source).range.startUtf16 },
                references(source.indexOf("val target") + 5),
            )
        }
    }

    @Test
    fun `references cross project files and exclude unrelated same spelling symbols`() {
        val declaration = "package sample\nfun target() = Unit"
        val firstUsage = "package sample\nfun first() = target()"
        val secondUsage =
            """
            package sample
            class Other {
                fun target() = Unit
                fun use() = target()
            }
            fun second() = target()
            """.trimIndent()
        K2QueryFixture
            .source(
                "declaration.kt" to declaration,
                "first.kt" to firstUsage,
                "second.kt" to secondUsage,
            ).use { fixture ->
                val result =
                    fixture.execute(
                        AnalysisQuery.References(
                            fixture.identity,
                            VirtualSourcePath.kotlin("declaration.kt"),
                            declaration.indexOf("target") + 1,
                        ),
                    ) as AnalysisResult.References

                assertEquals(
                    listOf(
                        sourceLocation("first.kt", firstUsage.lastIndexOf("target"), "target"),
                        sourceLocation("second.kt", secondUsage.lastIndexOf("target"), "target"),
                    ),
                    result.locations,
                )
            }
    }

    @Test
    fun `references reject output beyond the negotiated cap`() {
        val source = "fun target() = Unit\nfun use() { target(); target() }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            assertFailsWith<AnalysisOutputLimitException> {
                fixture.execute(
                    AnalysisQuery.References(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("target") + 1,
                    ),
                    AnalysisLimits(references = 1),
                )
            }
        }
    }

    private fun sourceLocation(
        path: String,
        start: Int,
        name: String,
    ) = DeclarationLocation.Source(
        DeclarationOrigin.Project,
        VirtualSourcePath.kotlin(path),
        EditorRange(start, start + name.length),
    )
}
