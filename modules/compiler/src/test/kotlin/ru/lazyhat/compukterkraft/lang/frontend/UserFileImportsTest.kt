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
                    "math.ck" to "pub fun add(): Int { return 1; } pub struct Vec2 { x: Int, y: Int }",
                    "main.ck" to "import \"math.ck\" { add, Vec2 }; pub fun main() { }",
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
                        pub fun main() {
                            val counter: Counter = Counter(value = 2);
                            system::log("value=" + counter.value);
                        }
                        """.trimIndent(),
                    "model.ck" to "pub class Counter(pub var value: Int) {}",
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
    fun selectiveImportCannotImportPrivateFunction() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "lib.ck" to "fun helper(): Int { return 1; }",
                    "main.ck" to "import \"lib.ck\" { helper }; pub fun main() {}",
                ),
            )

        val artifact = frontend.compile("main.ck", loader.read("main.ck")!!, loader)

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("no public export `helper`")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun publicImportCanCallPrivateHelperInImportedFile() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "lib.ck" to "fun helper(): Int { return 1; } pub fun api(): Int { return helper(); }",
                    "main.ck" to "import \"lib.ck\" { api }; pub fun main() { system::log(\"v=\" + api()); }",
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

        val artifact = frontend.compile("main.ck", "import \"math.ck\"; pub fun main() { }", loader)

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
        val artifact = frontend.compile("main.ck", "import system { log }; pub fun main() { log(\"hi\"); }")

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsBareBuiltinImport() {
        val artifact = frontend.compile("main.ck", "import system; pub fun main() { system::log(\"hi\"); }")

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Use `import system { name }`")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun selectiveBuiltinImportDoesNotExposeOtherMembers() {
        val artifact = frontend.compile("main.ck", "import system { log }; pub fun main() { deviceId(); }")

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR &&
                    (it.message.contains("Unknown function `deviceId`") || it.message.contains("Expression is not callable"))
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun selectiveBuiltinImportConflictsWithLocalFunction() {
        val artifact = frontend.compile("main.ck", "import system { log }; fun log() { }")

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
                    "math.ck" to "pub fun add(): Int { return 1; } fun hidden(): Int { return 2; }",
                    "main.ck" to "import \"math.ck\" { add }; pub fun main() { system::log(\"v=\" + add()); hidden(); }",
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
                    "math.ck" to "pub struct Vec2 { x: Int, y: Int } pub fun make(): Vec2 { return Vec2(x = 1, y = 2); }",
                    "main.ck" to
                        "import \"math.ck\" { Vec2, make }; pub fun main() { val v: Vec2 = make(); system::log(\"x=\" + v.x); }",
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
                        pub struct Vec2 { x: Int, y: Int }
                        pub fun add(a: Vec2, b: Vec2): Vec2 {
                            return Vec2(x = a.x + b.x, y = a.y + b.y);
                        }
                        """.trimIndent(),
                    "main.ck" to
                        """
                        import "math.ck" as m;
                        pub fun main() {
                            val v: m::Vec2 = m::Vec2(x = 1, y = 2);
                            val w: m::Vec2 = m::add(v, m::Vec2(x = 3, y = 4));
                            system::log("x=" + w.x);
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
                    "a.ck" to "pub fun helper(): Int { return 1; }",
                    "b.ck" to "pub fun helper(): Int { return 2; }",
                    "main.ck" to
                        """
                        import "a.ck" as a;
                        import "b.ck" as b;
                        pub fun main() {
                            system::log("a=" + a::helper());
                            system::log("b=" + b::helper());
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
        val loader = MapSourceLoader(mapOf("foo.ck" to "pub fun x(): Int { return 0; }"))

        val artifact = frontend.compile("main.ck", """import "foo.ck" as system; pub fun main() { }""", loader)

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
                    "a.ck" to "pub fun shared(): Int { return 1; }",
                    "b.ck" to "pub fun shared(): Int { return 2; }",
                    "main.ck" to
                        """
                        import "a.ck" { shared };
                        import "b.ck" { shared };
                        pub fun main() {}
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
        val loader = MapSourceLoader(mapOf("a.ck" to "pub fun util(): Int { return 1; }"))

        val artifact =
            frontend.compile(
                "main.ck",
                """
                import "a.ck" { util };
                fun util(): Int { return 0; }
                pub fun main() {}
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
                pub fun main() {}
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
                    "deep.ck" to "pub fun deep(): Int { return 7; }",
                    "mid.ck" to """import "deep.ck" { deep }; pub fun mid(): Int { return deep(); }""",
                    "main.ck" to
                        """
                        import "mid.ck" { mid };
                        pub fun main() { val z: Int = deep(); }
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
                    "a.ck" to """import "b.ck" { bFn }; pub fun aFn(): Int { return 1; }""",
                    "b.ck" to """import "a.ck" { aFn }; pub fun bFn(): Int { return 2; }""",
                    "main.ck" to
                        """
                        import "a.ck" { aFn };
                        import "b.ck" { bFn };
                        pub fun main() {
                            system::log("a=" + aFn() + " b=" + bFn());
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
                    "leaf.ck" to "pub fun leaf(): Int { return 9; }",
                    "left.ck" to """import "leaf.ck" as l; pub fun left(): Int { return l::leaf(); }""",
                    "right.ck" to """import "leaf.ck" as l; pub fun right(): Int { return l::leaf(); }""",
                    "main.ck" to
                        """
                        import "left.ck" { left };
                        import "right.ck" { right };
                        pub fun main() {
                            system::log("sum=" + (left() + right()));
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
