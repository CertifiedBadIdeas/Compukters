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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandCompletionTest {
    @Test
    fun `command members and inferred async batch results complete without a scope`() {
        for (attachedSources in listOf(false, true)) {
            val source =
                """
                import compukter.commands.Command
                import compukter.commands.batch
                import compukter.concurrent.async
                class Engine {
                    fun setThrottleCommand(throttle: Double): Command<Unit> = error("description fixture")
                }
                fun main() {
                    val engine = Engine()
                    engine.setThrottleC
                    val pending = async { batch(listOf(engine.setThrottleCommand(0.4))) }
                    pending.aw
                    val results = pending.await()
                    results[0].getOrT
                }
                """.trimIndent()
            K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to source).use { fixture ->
                fun returns(
                    marker: String,
                    name: String,
                ): String? =
                    fixture
                        .complete("main.kt", source.indexOf(marker) + marker.length)
                        .items
                        .single { it.insertText == name }
                        .callablePresentation
                        ?.returnType
                assertEquals("Command<Unit>", returns("engine.setThrottleC", "setThrottleCommand"))
                val awaited = returns("pending.aw", "await")
                assertTrue(awaited == "List<CommandResult<Unit>>", "await returned $awaited")
                assertEquals("Unit", returns("results[0].getOrT", "getOrThrow"))
            }
        }
    }
}
