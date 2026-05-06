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

class LanguageCollectionsSemanticTest {
    private val frontend = LanguageFrontend()

    @Test
    fun typechecksListMapArrayOperations() {
        val artifact =
            frontend.compile(
                "collections_semantic.ck",
                """
                pub fun main() {
                    val xs: List<Int> = [1, 2, 3];
                    xs.add(4);
                    xs[0] = xs.get(1);
                    val maybe: Int? = xs.getOrNull(99);

                    val fixed: Array<Int> = Array<Int>(size = 2, default = 0);
                    fixed[1] = xs[0];

                    val table: Map<String, Int> = {"a": 1};
                    table["b"] = fixed[1];
                    val present: Int? = table["a"];
                    val fallback: Int = table.getOrDefault("missing", 7);
                    val keys: List<String> = table.keys();
                    val values: List<Int> = table.values();
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none {
                it.severity == FrontendSeverity.ERROR
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsCollectionTypeMismatches() {
        val artifact =
            frontend.compile(
                "collections_bad.ck",
                """
                pub fun main() {
                    val xs: List<Int> = [1, 2];
                    xs.add("bad");
                    val table: Map<String, Int> = {"a": 1};
                    table[1] = 2;
                    val value: Int = table["a"];
                }
                """.trimIndent(),
            )

        val messages = artifact.analysis.diagnostics.joinToString { it.message }
        assertTrue(messages.contains("Expected Int, got String"), messages)
        assertTrue(messages.contains("Expected String, got Int"), messages)
        assertTrue(messages.contains("Expected Int, got Int?"), messages)
    }
}
