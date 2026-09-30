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

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RenameQueryTest {
    @Test
    fun `rename preserves class constructor and named parameter bindings`() {
        val source =
            "class Target(val value: Int)\nfun take(item: Target) = item.value\n" +
                "fun main() { val result = take(item = Target(value = 1)) }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            assertEquals(3, fixture.rename("main.kt", source.indexOf("Target"), "RenamedClass").locations.size)
            assertEquals(3, fixture.rename("main.kt", source.indexOf("value"), "renamedValue").locations.size)
            assertEquals(3, fixture.rename("main.kt", source.indexOf("item"), "renamedParameter").locations.size)
        }
    }

    @Test
    fun `rename refuses partial output and accepts unicode and escaped Kotlin identifiers`() {
        val source = "fun target() = 1\nfun use() = target()"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            assertFailsWith<AnalysisOutputLimitException> {
                fixture.execute(
                    AnalysisQuery.Rename(fixture.identity, VirtualSourcePath.kotlin("main.kt"), 4, "renamed"),
                    AnalysisLimits(references = 1),
                )
            }
            assertEquals(source, fixture.text("main.kt"))
            assertEquals(2, fixture.rename("main.kt", 4, "метод").locations.size)
            assertEquals(2, fixture.rename("main.kt", 4, "`when`").locations.size)
        }
    }

    @Test
    fun `failed preview restoration poisons the workspace instead of publishing speculative source`() {
        var updates = 0
        val updater =
            ru.lazyhat.compukters.ide.analysis.k2.standalone.K2SourceUpdater { environment, files, texts ->
                updates++
                if (updates == 2) error("restore failed")
                ru.lazyhat.compukters.ide.analysis.k2.standalone.DocumentK2SourceUpdater
                    .update(environment, files, texts)
            }
        K2QueryFixture.sourceWithUpdater(updater, "main.kt" to "fun target() = 1").use { fixture ->
            assertFailsWith<ru.lazyhat.compukters.ide.analysis.k2.standalone.K2WorkspaceReopenRequiredException> {
                fixture.rename("main.kt", 4, "renamed")
            }
            assertFailsWith<IllegalStateException> { fixture.snapshot }
        }
    }

    @Test
    fun `rename crosses files distinguishes overloads and restores original workspace`() {
        val declarations = "package sample\nfun target(value: Int) = value\nfun target(value: String) = value"
        val usage = "package sample\nfun use() { target(1); target(\"text\") }"
        K2QueryFixture.source("api.kt" to declarations, "use.kt" to usage).use { fixture ->
            val identity = fixture.identity
            val result = fixture.rename("api.kt", declarations.indexOf("target"), "renamedLonger")
            assertEquals(
                listOf("api.kt", "use.kt"),
                result.locations.map {
                    (it as ru.lazyhat.compukters.ide.analysis.DeclarationLocation.Source).path.value
                },
            )
            assertEquals(identity, fixture.identity)
            assertEquals(declarations, fixture.text("api.kt"))
            assertEquals(usage, fixture.text("use.kt"))
            assertEquals(result, fixture.rename("api.kt", declarations.indexOf("target"), "renamedLonger"))
        }
    }

    @Test
    fun `rename rejects silent capture of an existing binding and restores source`() {
        val source = "fun main() { val first = 1; if (true) { val second = 2; val sum = first + second } }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            assertFailsWith<RenameAdmissionException> { fixture.rename("main.kt", source.indexOf("first"), "second") }
            assertEquals(source, fixture.text("main.kt"))
            assertEquals(2, fixture.rename("main.kt", source.indexOf("first"), "renamed").locations.size)
        }
    }

    @Test
    fun `rename handles interpolation but leaves string text and aliases unchanged`() {
        val api = "package sample\nfun target() = 1"
        val usage = "import sample.target as alias\nfun use() = alias()"
        K2QueryFixture.source("api.kt" to api, "use.kt" to usage).use { fixture ->
            assertEquals(2, fixture.rename("api.kt", api.indexOf("target"), "renamed").locations.size)
        }
        val source = "fun main() { val item = 1; val text = \"${'$'}item ${'$'}{item} item\" }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            assertEquals(3, fixture.rename("main.kt", source.indexOf("item"), "longerItem").locations.size)
        }
    }

    @Test
    fun `rename rejects invalid names entrypoint collisions and override families`() {
        val source = "fun target() = 1\nfun other() = 2\nfun main() { val value: Int = target() }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            listOf("fun", "two words", "other", "bad.name").forEach { name ->
                assertFailsWith<RenameAdmissionException> { fixture.rename("main.kt", source.indexOf("target"), name) }
                assertEquals(source, fixture.text("main.kt"))
            }
            assertFailsWith<RenameAdmissionException> { fixture.rename("main.kt", source.indexOf("main"), "start") }
            assertFailsWith<RenameAdmissionException> { fixture.rename("main.kt", source.indexOf("Int"), "output") }
        }
        val inherited = "open class Base { open fun target() = 1 }\nclass Child : Base() { override fun target() = 2 }"
        K2QueryFixture.source("main.kt" to inherited).use { fixture ->
            assertFailsWith<RenameAdmissionException> { fixture.rename("main.kt", inherited.indexOf("target"), "renamed") }
        }
    }

    @Test
    fun `preview restores source and identity even when operation is cancelled`() {
        val source = "fun target() = 1"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val identity = fixture.identity
            assertFailsWith<ProcessCanceledException> {
                fixture.snapshot.preview(mapOf(VirtualSourcePath.kotlin("main.kt") to "fun renamed() = 1"), AnalysisLimits()) {
                    throw ProcessCanceledException()
                }
            }
            assertEquals(identity, fixture.identity)
            assertEquals(source, fixture.text("main.kt"))
            assertEquals(1, fixture.rename("main.kt", source.indexOf("target"), "renamed").locations.size)
        }
    }
}

private fun K2QueryFixture.rename(
    path: String,
    offset: Int,
    name: String,
): AnalysisResult.References =
    execute(AnalysisQuery.Rename(identity, VirtualSourcePath.kotlin(path), offset, name)) as AnalysisResult.References

private fun K2QueryFixture.text(path: String): String =
    ReadAction.compute<String, RuntimeException> {
        snapshot.files.getValue(VirtualSourcePath.kotlin(path)).text
    }
