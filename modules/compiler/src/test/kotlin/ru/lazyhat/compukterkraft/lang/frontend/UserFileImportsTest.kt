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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UserFileImportsTest {
    private val frontend = LanguageFrontend()

    @Test
    fun aliasedImportExposesNamespace() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "math.ck" to
                        """
                        struct Vec2 { x: Int, y: Int }
                        fun add(a: Vec2, b: Vec2): Vec2 {
                            return Vec2 { x: a.x + b.x, y: a.y + b.y };
                        }
                        """.trimIndent(),
                    "main.ck" to
                        """
                        import "math.ck" as m;
                        fun main() {
                            val v: m::Vec2 = m::Vec2 { x: 1, y: 2 };
                            val w: m::Vec2 = m::add(v, m::Vec2 { x: 3, y: 4 });
                            terminal::println("x=" + w.x);
                        }
                        """.trimIndent(),
                ),
            )

        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun flatImportsFromDifferentFilesShareNoNamesByMangling() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "a.ck" to "fun helper(): Int { return 1; }",
                    "b.ck" to "fun helper(): Int { return 2; }",
                    "main.ck" to
                        """
                        import "a.ck" as a;
                        import "b.ck" as b;
                        fun main() {
                            terminal::println("a=" + a::helper());
                            terminal::println("b=" + b::helper());
                        }
                        """.trimIndent(),
                ),
            )

        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun aliasCollidesWithBuiltinModule() {
        val loader = MapSourceLoader(mapOf("foo.ck" to "fun x(): Int { return 0; }"))

        val artifact = frontend.compile("main.ck", """import "foo.ck" as terminal; fun main() { }""", loader)

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Redeclaration")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun flatImportsClashOnSameName() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "a.ck" to "fun shared(): Int { return 1; }",
                    "b.ck" to "fun shared(): Int { return 2; }",
                    "main.ck" to
                        """
                        import "a.ck";
                        import "b.ck";
                        fun main() {}
                        """.trimIndent(),
                ),
            )

        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Redeclaration")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun flatImportClashesWithLocalFunction() {
        val loader = MapSourceLoader(mapOf("a.ck" to "fun util(): Int { return 1; }"))

        val artifact =
            frontend.compile(
                "main.ck",
                """
                import "a.ck";
                fun util(): Int { return 0; }
                fun main() {}
                """.trimIndent(),
                loader,
            )

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Redeclaration")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun duplicateImportPathDiagnostic() {
        val loader = MapSourceLoader(mapOf("x.ck" to "fun a(): Int { return 0; }"))

        val artifact =
            frontend.compile(
                "main.ck",
                """
                import "x.ck";
                import "x.ck" as x;
                fun main() {}
                """.trimIndent(),
                loader,
            )

        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Duplicate import") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun importsAreNotTransitive() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "deep.ck" to "fun deep(): Int { return 7; }",
                    "mid.ck" to """import "deep.ck"; fun mid(): Int { return deep(); }""",
                    "main.ck" to
                        """
                        import "mid.ck";
                        fun main() { val z: Int = deep(); }
                        """.trimIndent(),
                ),
            )

        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Expression is not callable")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }
}
