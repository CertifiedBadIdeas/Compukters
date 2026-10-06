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
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticTokenQueryTest {
    @Test
    fun `peripheral provider expressions retain a distinct role from device type references`() {
        val source =
            """
            import compukter.display.TextDisplay
            import fixture.kinetics.AddonPeripheral
            import compukter.peripheral.*
            class Meter : Peripheral {
                companion object : TypedPeripheralProvider<Meter>("test:meter") {
                    override fun wrap(handle: Int): Meter = Meter()
                }
            }
            object Standalone : TypedPeripheralProvider<Meter>("test:meter") {
                override fun wrap(handle: Int): Meter = Meter()
            }
            object FakeProvider
            fun use(device: TextDisplay, meter: Meter, addon: AddonPeripheral) {
                TextDisplay.firstOrNull()
                AddonPeripheral.firstOrNull()
                Meter.firstOrNull()
                Standalone.firstOrNull()
                FakeProvider.toString()
            }
            """.trimIndent()
        for (attached in listOf(false, true)) {
            K2QueryFixture.sourceWithGuestApi(attached, "main.kt" to source).use { fixture ->
                val presentation = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
                val active = presentation.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

                fun roleAt(
                    fragment: String,
                    offset: Int = 0,
                ): SemanticCategory? =
                    active.semanticTokens
                        .singleOrNull {
                            it.range.startUtf16 == source.indexOf(fragment) + offset &&
                                it.category !in setOf(SemanticCategory.InferredExpression, SemanticCategory.SmartCastExpression)
                        }?.category
                assertEquals(SemanticCategory.PeripheralProvider, roleAt("TextDisplay.first"))
                assertEquals(SemanticCategory.PeripheralProvider, roleAt("Meter.first"))
                assertEquals(SemanticCategory.PeripheralProvider, roleAt("AddonPeripheral.first"))
                assertEquals(SemanticCategory.Class, roleAt("addon: AddonPeripheral", "addon: ".length))
                assertEquals(SemanticCategory.PeripheralProvider, roleAt("Standalone.first"))
                assertEquals(SemanticCategory.PeripheralProvider, roleAt("object Standalone", "object ".length))
                assertEquals(
                    SemanticCategory.Class,
                    roleAt("device: TextDisplay", "device: ".length),
                    active.semanticTokens.joinToString {
                        "${source.substring(it.range.startUtf16, it.range.endUtf16)}@${it.range.startUtf16}:${it.category}"
                    },
                )
                assertEquals(SemanticCategory.Class, roleAt("meter: Meter", "meter: ".length))
                assertEquals(SemanticCategory.Object, roleAt("FakeProvider.toString"))
            }
        }
    }

    @Test
    fun `method usage counts distinguish overloads members and local functions across project files`() {
        val declarations =
            """
            fun work(value: Int) = value
            fun work(value: String) = value
            class Box { fun work() = 1 }
            fun unused() = 0
            """.trimIndent()
        val calls =
            """
            fun main() {
                work(1)
                work(2)
                work("text")
                Box().work()
                fun work() = 2
                work()
            }
            """.trimIndent()
        K2QueryFixture.source("decl.kt" to declarations, "uses.kt" to calls).use { fixture ->
            val result = fixture.execute(fixture.presentation("decl.kt")) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
            assertEquals(listOf(2, 1, 1, 0), active.methodUsages.map { it.count })
            fixture.update("uses.kt" to "fun main() = work(1)")
            val updated = fixture.execute(fixture.presentation("decl.kt")) as AnalysisResult.Presentation
            assertEquals(
                listOf(
                    1,
                    0,
                    0,
                    0,
                ),
                (updated.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active).methodUsages.map {
                    it.count
                },
            )
        }
    }

    @Test
    fun `presentation classifies declarations and extension functions`() {
        val source = "class Box(val value: Int)\nfun Box.doubled() = value * 2"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
            val categories = active.semanticTokens.map { it.category }.toSet()

            assertTrue(SemanticCategory.Class in categories, active.semanticTokens.toString())
            assertTrue(SemanticCategory.Property in categories, active.semanticTokens.toString())
            assertTrue(SemanticCategory.ExtensionFunction in categories, active.semanticTokens.toString())
            assertTrue(active.semanticTokens.count { it.category == SemanticCategory.Property } >= 2, active.semanticTokens.toString())
        }
    }

    @Test
    fun `presentation marks inferred and smart cast expressions`() {
        val source =
            """
            fun length(value: Any): Int {
                val fallback = 0
                if (value is String) return value.length
                return fallback
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active
            val categories = active.semanticTokens.map { it.category }.toSet()

            assertTrue(SemanticCategory.InferredExpression in categories, active.semanticTokens.toString())
            assertTrue(SemanticCategory.SmartCastExpression in categories, active.semanticTokens.toString())
        }
    }

    @Test
    fun `presentation marks mutable declarations and references`() {
        val source =
            """
            var mutableProperty = 0
            val immutableProperty = mutableProperty
            fun update() {
                var mutableLocal = mutableProperty
                val immutableLocal = mutableLocal
                mutableLocal += immutableLocal
                mutableProperty = mutableLocal
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

            val variableCategories = setOf(SemanticCategory.Property, SemanticCategory.LocalVariable)
            val mutableTokens =
                active.semanticTokens.filter {
                    it.category in variableCategories && source.substring(it.range.startUtf16, it.range.endUtf16).startsWith("mutable")
                }
            val immutableTokens =
                active.semanticTokens.filter {
                    it.category in variableCategories && source.substring(it.range.startUtf16, it.range.endUtf16).startsWith("immutable")
                }

            assertTrue(mutableTokens.isNotEmpty(), active.semanticTokens.toString())
            assertTrue(mutableTokens.all { it.isMutable }, mutableTokens.toString())
            assertTrue(immutableTokens.isNotEmpty(), active.semanticTokens.toString())
            assertTrue(immutableTokens.none { it.isMutable }, immutableTokens.toString())
        }
    }

    @Test
    fun `presentation returns semantic tokens only for its active file`() {
        K2QueryFixture
            .source(
                "a.kt" to "class ActiveFile",
                "b.kt" to "class ClosedFile",
            ).use { fixture ->
                val result =
                    fixture.execute(
                        fixture.presentation("b.kt"),
                    ) as AnalysisResult.Presentation
                val active = result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active

                assertEquals(setOf("b.kt"), active.semanticTokens.map { it.path.value }.toSet())
            }
    }
}
