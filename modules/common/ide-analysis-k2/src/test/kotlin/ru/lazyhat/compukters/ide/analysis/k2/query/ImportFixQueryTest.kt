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
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportFixQueryTest {
    @Test
    fun `exact name imports preserve calls and restore admitted snapshot`() {
        val source = "fun main() { greet(2) }"
        K2QueryFixture
            .source(
                "main.kt" to source,
                "lib.kt" to "package sample\nfun greet(times: Int) = Unit\nfun greeting() = Unit",
            ).use { fixture ->
                val identity = fixture.identity
                val result = fixture.fixes(source.indexOf("greet") + "greet".length)
                assertEquals(listOf("sample.greet"), result.items.map { it.fqName })
                val edit = result.items.single().edit
                assertEquals("import sample.greet\n\n", edit.text)
                assertEquals(0, edit.range.startUtf16)
                assertEquals(
                    source,
                    fixture.snapshot.files
                        .getValue(path)
                        .text,
                )
                assertEquals(identity, fixture.identity)
                fixture.update("main.kt" to edit.text + source)
                assertTrue(fixture.fixes((edit.text + source).lastIndexOf("greet")).items.isEmpty())
            }
    }

    @Test
    fun `competing packages remain distinct and incompatible overloads are excluded`() {
        val source = "fun main() { greet(2) }"
        K2QueryFixture
            .source(
                "main.kt" to source,
                "a.kt" to "package a\nfun greet(times: Int) = Unit",
                "b.kt" to "package b\nfun greet(times: Int) = Unit",
                "c.kt" to "package c\nfun greet(text: String) = Unit",
            ).use { fixture ->
                assertEquals(listOf("a.greet", "b.greet"), fixture.fixes(source.indexOf("greet")).items.map { it.fqName })
            }
    }

    @Test
    fun `classes properties unicode and existing package imports retain line endings`() {
        val source = "package app\r\nimport kotlin.Unit\r\nfun main() { val text = \"😀\"; val box: Box = Box(); value }"
        K2QueryFixture.source("main.kt" to source, "lib.kt" to "package sample\nclass Box\nval value = 1").use { fixture ->
            val fixes = fixture.fixes(source.indexOf("Box"))
            assertEquals(listOf("sample.Box"), fixes.items.map { it.fqName })
            assertEquals(
                "\r\nimport sample.Box",
                fixes.items
                    .single()
                    .edit.text,
            )
            assertEquals(
                source.indexOf("\r\nfun main"),
                fixes.items
                    .single()
                    .edit.range.startUtf16,
            )
            assertEquals(listOf("sample.value"), fixture.fixes(source.indexOf("value")).items.map { it.fqName })
        }
    }

    @Test
    fun `resolved code comments literals and private declarations receive no fix`() {
        val source = "fun greet() = Unit\nfun main() { greet(); hidden(); val text = \"missing\"; /* missing */ }"
        K2QueryFixture
            .source(
                "main.kt" to source,
                "lib.kt" to "package sample\nfun missing() = Unit\nprivate fun hidden() = Unit",
            ).use { fixture ->
                for (offset in listOf(
                    source.indexOf("greet();"),
                    source.indexOf("hidden"),
                    source.indexOf("missing"),
                    source.lastIndexOf("missing"),
                )) {
                    assertTrue(fixture.fixes(offset).items.isEmpty())
                }
            }
    }

    @Test
    fun `import conflicts and rebinding other references are rejected`() {
        val source = "import a.*\nfun main() { greet(2); val value: Int = greet }"
        K2QueryFixture
            .source(
                "main.kt" to source,
                "a.kt" to "package a\nval greet: Int = 1",
                "b.kt" to "package b\nfun greet(times: Int) = Unit",
            ).use { fixture ->
                assertTrue(fixture.fixes(source.indexOf("greet(2)")).items.isEmpty())
            }
        val conflict = "import a.greet\nfun main() { greet(2) }"
        K2QueryFixture
            .source(
                "main.kt" to conflict,
                "a.kt" to "package a\nfun greet(text: String) = Unit",
                "b.kt" to "package b\nfun greet(times: Int) = Unit",
            ).use { fixture ->
                assertTrue(fixture.fixes(conflict.lastIndexOf("greet")).items.isEmpty())
            }
    }

    @Test
    fun `unrelated errors survive and syntax errors suppress speculation`() {
        val source = "fun main() { greet(2); missing }"
        K2QueryFixture.source("main.kt" to source, "lib.kt" to "package sample\nfun greet(times: Int) = Unit").use { fixture ->
            assertEquals(listOf("sample.greet"), fixture.fixes(source.indexOf("greet")).items.map { it.fqName })
            assertTrue(fixture.fixes(source.indexOf("greet"), AnalysisLimits(completionItems = 0)).items.isEmpty())
        }
        K2QueryFixture.source("main.kt" to "fun main() { greet(", "lib.kt" to "package sample\nfun greet() = Unit").use { fixture ->
            assertTrue(fixture.fixes(13).items.isEmpty())
        }
    }

    @Test
    fun `enabled platform modules provide imports but inactive modules do not`() {
        val source = "fun main() { Sound.beep(12) }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            assertEquals(listOf("compukter.sound.Sound"), fixture.fixes(source.indexOf("Sound")).items.map { it.fqName })
        }
        K2QueryFixture.sourceWithInactiveGuestApi("main.kt" to source).use { fixture ->
            assertTrue(fixture.fixes(source.indexOf("Sound")).items.isEmpty())
        }
    }

    private fun K2QueryFixture.fixes(
        offset: Int,
        limits: AnalysisLimits = AnalysisLimits(),
    ) = execute(AnalysisQuery.ImportFixes(identity, path, offset), limits) as AnalysisResult.ImportFixes

    companion object {
        private val path = VirtualSourcePath.kotlin("main.kt")
    }
}
