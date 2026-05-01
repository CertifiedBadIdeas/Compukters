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
    fun parsesSelectiveFileImport() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "math.ck" to "fun add(): Int { return 1; } struct Vec2 { x: Int, y: Int }",
                    "main.ck" to "import \"math.ck\" { add, Vec2 }; fun main() { }",
                ),
            )

        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun selectiveImportCanImportClass() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "main.ck" to
                        """
                        import "model.ck" { Counter };
                        fun main() {
                            val counter: Counter = Counter(value = 2);
                            terminal::println("value=" + counter.value);
                        }
                        """.trimIndent(),
                    "model.ck" to "class Counter(var value: Int) {}",
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
    fun rejectsFlatFileImport() {
        val loader = MapSourceLoader(mapOf("math.ck" to "fun add(): Int { return 1; }"))

        val artifact = frontend.compile("main.ck", "import \"math.ck\"; fun main() { }", loader)

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR &&
                    it.message.contains("Use `import \"math.ck\" { name }` or `import \"math.ck\" as alias`")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun parsesSelectiveBuiltinImport() {
        val artifact = frontend.compile("main.ck", "import terminal { println }; fun main() { println(\"hi\"); }")

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsBareBuiltinImport() {
        val artifact = frontend.compile("main.ck", "import terminal; fun main() { terminal::println(\"hi\"); }")

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Use `import terminal { name }`")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun selectiveBuiltinImportDoesNotExposeOtherMembers() {
        val artifact = frontend.compile("main.ck", "import terminal { println }; fun main() { clear(); }")

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR &&
                    (it.message.contains("Unknown function `clear`") || it.message.contains("Expression is not callable"))
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun selectiveBuiltinImportConflictsWithLocalFunction() {
        val artifact = frontend.compile("main.ck", "import terminal { println }; fun println() { }")

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Redeclaration")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun selectiveFileImportExposesOnlySelectedFunction() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "math.ck" to "fun add(): Int { return 1; } fun hidden(): Int { return 2; }",
                    "main.ck" to "import \"math.ck\" { add }; fun main() { terminal::println(\"v=\" + add()); hidden(); }",
                ),
            )

        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR &&
                    (it.message.contains("Unknown function `hidden`") || it.message.contains("Expression is not callable"))
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun selectiveFileImportExposesStructTypeAndConstructor() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "math.ck" to "struct Vec2 { x: Int, y: Int } fun make(): Vec2 { return Vec2(x = 1, y = 2); }",
                    "main.ck" to "import \"math.ck\" { Vec2, make }; fun main() { val v: Vec2 = make(); terminal::println(\"x=\" + v.x); }",
                ),
            )

        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun aliasedImportExposesNamespace() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "math.ck" to
                        """
                        struct Vec2 { x: Int, y: Int }
                        fun add(a: Vec2, b: Vec2): Vec2 {
                            return Vec2(x = a.x + b.x, y = a.y + b.y);
                        }
                        """.trimIndent(),
                    "main.ck" to
                        """
                        import "math.ck" as m;
                        fun main() {
                            val v: m::Vec2 = m::Vec2(x = 1, y = 2);
                            val w: m::Vec2 = m::add(v, m::Vec2(x = 3, y = 4));
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
        assertTrue(artifact.module.functions.any { it.name == "a.ck#helper" })
        assertTrue(artifact.module.functions.any { it.name == "b.ck#helper" })
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
    fun selectiveImportsClashOnSameName() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "a.ck" to "fun shared(): Int { return 1; }",
                    "b.ck" to "fun shared(): Int { return 2; }",
                    "main.ck" to
                        """
                        import "a.ck" { shared };
                        import "b.ck" { shared };
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
    fun selectiveImportClashesWithLocalFunction() {
        val loader = MapSourceLoader(mapOf("a.ck" to "fun util(): Int { return 1; }"))

        val artifact =
            frontend.compile(
                "main.ck",
                """
                import "a.ck" { util };
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
                import "x.ck" { a };
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
                    "mid.ck" to """import "deep.ck" { deep }; fun mid(): Int { return deep(); }""",
                    "main.ck" to
                        """
                        import "mid.ck" { mid };
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

    @Test
    fun importGraphCycleDoesNotInfinitelyRecurse() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "a.ck" to """import "b.ck" { bFn }; fun aFn(): Int { return 1; }""",
                    "b.ck" to """import "a.ck" { aFn }; fun bFn(): Int { return 2; }""",
                    "main.ck" to
                        """
                        import "a.ck" { aFn };
                        import "b.ck" { bFn };
                        fun main() {
                            terminal::println("a=" + aFn() + " b=" + bFn());
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
    fun diamondImportCompilesOncePerFile() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "leaf.ck" to "fun leaf(): Int { return 9; }",
                    "left.ck" to """import "leaf.ck" as l; fun left(): Int { return l::leaf(); }""",
                    "right.ck" to """import "leaf.ck" as l; fun right(): Int { return l::leaf(); }""",
                    "main.ck" to
                        """
                        import "left.ck" { left };
                        import "right.ck" { right };
                        fun main() {
                            terminal::println("sum=" + (left() + right()));
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
}
