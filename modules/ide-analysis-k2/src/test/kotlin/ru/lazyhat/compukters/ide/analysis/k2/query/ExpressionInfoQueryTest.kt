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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExpressionInfoQueryTest {
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
}
