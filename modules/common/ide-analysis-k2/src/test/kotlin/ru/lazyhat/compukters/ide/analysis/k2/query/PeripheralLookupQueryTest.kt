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

import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.SnapshotPresentationAcceptance
import kotlin.test.Test
import kotlin.test.assertEquals

class PeripheralLookupQueryTest {
    @Test
    fun `project wrappers and overridden methods cannot imply a host lookup contract`() {
        val source =
            """
            import compukter.peripheral.*
            class Wrapper : Peripheral {
                companion object : TypedPeripheralProvider<Wrapper>("test:wrapper") {
                    override fun wrap(handle: Int) = Wrapper()
                }
            }
            fun use() { Wrapper.named("device") }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
            assertEquals(emptyList(), (result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active).peripheralLookups)
        }
    }

    @Test
    fun `named lookups resolve constants aliases and addon types without matching unrelated calls`() {
        val source =
            """
            import compukter.display.TextDisplay as Screen
            import fixture.kinetics.AddonPeripheral
            const val SCREEN = "screen"
            object Fake { fun named(name: String) = name }
            fun use(dynamic: String) {
                Screen.named(SCREEN)
                Screen.namedOrNull(name = "optional")
                AddonPeripheral.named("addon")
                Screen.named(dynamic)
                Fake.named("fake")
            }
            """.trimIndent()
        for (attached in listOf(false, true)) {
            K2QueryFixture.sourceWithGuestApi(attached, "main.kt" to source).use { fixture ->
                val result = fixture.execute(fixture.presentation()) as AnalysisResult.Presentation
                val facts = (result.value.accept(fixture.identity) as SnapshotPresentationAcceptance.Active).peripheralLookups
                assertEquals(listOf("screen", "optional", "addon"), facts.map { it.name })
                assertEquals(listOf(false, true, false), facts.map { it.optional })
                assertEquals(
                    listOf(
                        "compukter.display.TextDisplay",
                        "compukter.display.TextDisplay",
                        "fixture.kinetics.AddonPeripheral",
                    ),
                    facts.map {
                        it.type
                    },
                )
                assertEquals(
                    listOf("SCREEN", "\"optional\"", "\"addon\""),
                    facts.map { source.substring(it.range.startUtf16, it.range.endUtf16) },
                )
            }
        }
    }
}
