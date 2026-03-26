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

package ru.lazyhat.ck.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageFrontendTest {
    private val frontend = LanguageFrontend()

    @Test
    fun compilesRecordsFunctionsAndBuiltins() {
        val artifact =
            frontend.compile(
                "test.ck",
                """
                import terminal;

                struct Point {
                    x: Int,
                    y: Int
                }

                fun sum(point: Point): Int {
                    return point.x + point.y;
                }

                fun main() {
                    val point: Point = Point { x: 1, y: 2 };
                    terminal.printLine("sum=" + sum(point));
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { "${it.range}, ${it.message}" },
        )
        assertNotNull(artifact.module)
        assertTrue(artifact.analysis.symbols.any { it.name == "Point" })
        assertTrue(artifact.analysis.references.any { it.name == "printLine" })
    }

    @Test
    fun reportsTypeMismatchDiagnostics() {
        val artifact =
            frontend.compile(
                "broken.ck",
                """
                fun main() {
                    val flag: Bool = 42;
                }
                """.trimIndent(),
            )

        assertEquals(artifact.module, null)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Expected Bool") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }
}
