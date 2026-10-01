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
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExpressionInfoQueryTest {
    @Test
    fun `hover documentation follows resolved overload and declaration names`() {
        val source =
            """
            /** Integer documentation. */
            fun value(x: Int) = x
            /** String documentation.
             * @param x the text
             * @return the same text
             */
            fun value(x: String) = x
            /** Box documentation. */
            class Box
            fun main() { value(1); value("hello"); Box() }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            fun hover(offset: Int) =
                assertNotNull(
                    (
                        fixture.execute(
                            AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                        ) as AnalysisResult.ExpressionInfo
                    ).value,
                )
            assertEquals("Integer documentation.", hover(source.indexOf("fun value") + 4).documentation)
            assertEquals("Integer documentation.", hover(source.lastIndexOf("value(1)")).documentation)
            val string = assertNotNull(hover(source.lastIndexOf("value(\"hello\")")).documentation)
            assertTrue(string.startsWith("String documentation."))
            assertTrue(string.contains("@param x the text"))
            assertTrue(string.contains("@return the same text"))
            assertEquals("Box documentation.", hover(source.indexOf("class Box") + 6).documentation)
            assertEquals("Box documentation.", hover(source.lastIndexOf("Box()")).documentation)
            assertEquals(null, hover(source.indexOf("fun main") + 4).documentation)
        }
    }

    @Test
    fun `hover documentation comes from exact attached platform source`() {
        val source = "fun main() { \"hello\".takeIf { true } }"
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            val info =
                assertNotNull(
                    (
                        fixture.execute(
                            AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), source.indexOf("takeIf")),
                        ) as AnalysisResult.ExpressionInfo
                    ).value,
                )
            assertEquals("Returns this value when [predicate] is true, or null otherwise.", info.documentation)
        }
    }

    @Test
    fun `expression query renders the inferred type on a local declaration name`() {
        val source = "fun main() { val k = 2 }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val start = source.indexOf("k")
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        start,
                    ),
                ) as AnalysisResult.ExpressionInfo
            val info = assertNotNull(result.value)

            assertEquals("kotlin.Int", info.renderedType)
            assertEquals(EditorRange(start, start + 1), info.range)
        }
    }

    @Test
    fun `expression query renders inferred Long arithmetic`() {
        val source = "fun main() { val total = 3_000_000_000L + 7 }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val start = source.indexOf("total")
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        start,
                    ),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("kotlin.Long", assertNotNull(result.value).renderedType)
        }
    }

    @Test
    fun `expression query renders the explicit type on a local declaration name`() {
        val source = "fun main() { val message: String = \"ok\" }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val start = source.indexOf("message")
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        start,
                    ),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("kotlin.String", assertNotNull(result.value).renderedType)
        }
    }

    @Test
    fun `expression query reports project origin`() {
        val source = "fun local() = Unit\nfun main() = local()"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.lastIndexOf("local") + 1,
                    ),
                ) as AnalysisResult.ExpressionInfo

            assertEquals(DeclarationOrigin.Project, assertNotNull(result.value).origin)
        }
    }

    @Test
    fun `expression query reports attached Guest API bundle origin`() {
        val source = "import compukter.terminal.Terminal\nfun main() = Terminal.write(\"ok\")"
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("write") + 1,
                    ),
                ) as AnalysisResult.ExpressionInfo

            val bundle = assertIs<DeclarationOrigin.Platform>(assertNotNull(result.value).origin)
            assertEquals("compukter:core", bundle.identity.name)
        }
    }

    @Test
    fun `expression query renders an inferred local type`() {
        val source = "fun main() { val answer = \"ok\"; println(answer) }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offset = source.lastIndexOf("answer") + 1
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("kotlin.String", assertNotNull(result.value).renderedType)
        }
    }

    @Test
    fun `expression query renders a resolved callable signature`() {
        val source = "fun greet(name: String): String = name\nval result = greet(\"Ada\")"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offset = source.lastIndexOf("greet") + 1
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                ) as AnalysisResult.ExpressionInfo
            val info = assertNotNull(result.value)

            assertEquals("kotlin.String", info.renderedType)
            assertTrue(assertNotNull(info.signature).contains("greet"), info.signature)
        }
    }

    @Test
    fun `expression query reports a smart cast type`() {
        val source = "fun length(value: Any): Int { if (value is String) return value.length; return 0 }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offset = source.lastIndexOf("value") + 1
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("kotlin.String", assertNotNull(result.value).renderedType)
        }
    }

    @Test
    fun `expression query preserves the nominal type of an inline value class`() {
        val source =
            """
            @JvmInline
            value class Signal(val value: Int)

            fun main() {
                val signal = Signal(7)
                println(signal)
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offset = source.lastIndexOf("signal") + 1
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("Signal", assertNotNull(result.value).renderedType)
        }
    }
}
