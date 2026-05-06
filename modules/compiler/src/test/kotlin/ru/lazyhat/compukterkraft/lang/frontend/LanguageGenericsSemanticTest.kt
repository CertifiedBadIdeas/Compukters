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
import kotlin.test.assertTrue

class LanguageGenericsSemanticTest {
    private val frontend = LanguageFrontend()

    @Test
    fun acceptsGenericTypeParametersInScope() {
        val artifact =
            frontend.compile(
                "generic_scope.ck",
                """
                pub struct Box<T> { value: T }
                pub fun identity<T>(value: T): T { return value; }
                pub fun main() {}
                """.trimIndent(),
            )

        assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
    }

    @Test
    fun rejectsGenericCollectionTypeMismatch() {
        val artifact =
            frontend.compile(
                "generic_mismatch.ck",
                """
                pub fun acceptStrings(xs: List<String>) {}
                pub fun passInts(xs: List<Int>) {
                    acceptStrings(xs);
                }
                pub fun main() {}
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Expected List<String>, got List<Int>") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun substitutesGenericFunctionStructAndClassTypes() {
        val artifact =
            frontend.compile(
                "generic_substitution.ck",
                """
                pub struct Pair<A, B> { first: A, second: B }
                pub class Box<T>(pub var value: T) {
                    pub fun current(): T { return this.value; }
                }
                pub fun identity<T>(value: T): T { return value; }
                pub fun main() {
                    val answer: Int = identity(42);
                    val pair: Pair<String, Int> = Pair(first = "x", second = answer);
                    val box: Box<String> = Box(value = pair.first);
                    val text: String = box.current();
                }
                """.trimIndent(),
            )

        assertTrue(artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR }, artifact.analysis.diagnostics.joinToString { it.message })
    }
}
