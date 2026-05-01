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

import ru.lazyhat.compukterkraft.lang.api.TokenKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageFrontendTest {
    private val frontend = LanguageFrontend()

    @Test
    fun lexesDoubleColonAsScopeOperator() {
        val tokens = Lexer("fun main() { terminal::println(\"ok\"); }").lex()

        assertTrue(tokens.any { it.kind == TokenKind.COLON_COLON }, tokens.joinToString { "${it.kind}:${it.text}" })
    }

    @Test
    fun parsesScopeCallToBuiltin() {
        val artifact =
            frontend.compile(
                "ok.ck",
                """
                fun main() { terminal::println("hi"); }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun rejectsDotForBuiltinModuleAccess() {
        val artifact =
            frontend.compile(
                "dot.ck",
                """
                fun main() { terminal.println("hi"); }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Use `::` for module access")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsImportDeclarationsHard() {
        val artifact =
            frontend.compile(
                "import.ck",
                """
                import terminal;
                fun main() { terminal::println("ok"); }
                """.trimIndent(),
            )
        val errors = artifact.analysis.diagnostics.filter { it.severity == FrontendSeverity.ERROR }

        assertTrue(
            errors.any { it.message.contains("Built-in modules are available without `import`") },
            errors.joinToString { it.message },
        )
        assertEquals(null, artifact.module)
    }

    @Test
    fun ambientBuiltinsWorkWithoutImport() {
        val artifact =
            frontend.compile(
                "ambient.ck",
                """
                fun main() { terminal::println("ok"); }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun rejectsQualifiedTypesUntilUserImportsLand() {
        val artifact =
            frontend.compile(
                "qual.ck",
                """
                fun main() { val v: m::Foo = null; }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR &&
                    it.message.contains("Qualified types are not yet supported")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun compilesRecordsFunctionsAndBuiltins() {
        val artifact =
            frontend.compile(
                "test.ck",
                """
                struct Point {
                    x: Int,
                    y: Int
                }

                fun sum(point: Point): Int {
                    return point.x + point.y;
                }

                fun main() {
                    val point: Point = Point { x: 1, y: 2 };
                    terminal::println("sum=" + sum(point));
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { "${it.range}, ${it.message}" },
        )
        assertNotNull(artifact.module)
        assertTrue(artifact.analysis.symbols.any { it.name == "Point" })
        assertTrue(artifact.analysis.references.any { it.name == "println" })
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

    @Test
    fun compilesElseIfChains() {
        val artifact =
            frontend.compile(
                "elseif.ck",
                """
                fun main() {
                    val x: Int = 2;
                    if (x == 1) {
                        terminal::println("one");
                    } else if (x == 2) {
                        terminal::println("two");
                    } else if (x == 3) {
                        terminal::println("three");
                    } else {
                        terminal::println("other");
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { "${it.range}, ${it.message}" },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun compilesWhenWithSubject() {
        val artifact =
            frontend.compile(
                "when_subject.ck",
                """
                fun main() {
                    val x: Int = 2;
                    when(x) {
                        1 -> {
                            terminal::println("one");
                        }
                        2, 3 -> {
                            terminal::println("two or three");
                        }
                        else -> {
                            terminal::println("other");
                        }
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { "${it.range}, ${it.message}" },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun compilesWhenWithoutSubject() {
        val artifact =
            frontend.compile(
                "when_no_subject.ck",
                """
                fun main() {
                    val x: Int = 5;
                    when {
                        x > 10 -> {
                            terminal::println("big");
                        }
                        x > 0 -> {
                            terminal::println("positive");
                        }
                        else -> {
                            terminal::println("non-positive");
                        }
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { "${it.range}, ${it.message}" },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun reportsWhenBranchTypeMismatch() {
        val artifact =
            frontend.compile(
                "when_mismatch.ck",
                """
                fun main() {
                    val x: Int = 1;
                    when(x) {
                        "hello" -> {
                            val y: Int = 1;
                        }
                    }
                }
                """.trimIndent(),
            )

        assertEquals(artifact.module, null)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("When branch value type mismatch") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun reportsWhenConditionMustBeBool() {
        val artifact =
            frontend.compile(
                "when_bool.ck",
                """
                fun main() {
                    when {
                        42 -> {
                            val y: Int = 1;
                        }
                    }
                }
                """.trimIndent(),
            )

        assertEquals(artifact.module, null)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Expected Bool") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun reportsElseFollowedByNonIfStatement() {
        val cases =
            listOf(
                "else_while.ck" to """
                fun main() {
                    if (true) {
                        val x: Int = 1;
                    } else while
                }
            """,
                "else_val.ck" to """
                fun main() {
                    if (true) {
                        val x: Int = 1;
                    } else val
                }
            """,
                "else_return.ck" to """
                fun main() {
                    if (true) {
                        val x: Int = 1;
                    } else return
                }
            """,
                "else_when.ck" to """
                fun main() {
                    if (true) {
                        val x: Int = 1;
                    } else when
                }
            """,
            )

        for ((name, source) in cases) {
            val artifact = frontend.compile(name, source.trimIndent())
            assertTrue(
                artifact.analysis.diagnostics.any {
                    it.severity == FrontendSeverity.ERROR
                },
                "Expected parse error for $name but got: ${artifact.analysis.diagnostics.joinToString { it.message }}",
            )
        }
    }

    @Test
    fun reportsIfWithoutParentheses() {
        val artifact =
            frontend.compile(
                "if_no_parens.ck",
                """
                fun main() {
                    if true {
                        val x: Int = 1;
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.any {
                it.severity == FrontendSeverity.ERROR && it.message.contains("Expected `(`")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun compilesAssignmentToVar() {
        val artifact =
            frontend.compile(
                "assign.ck",
                """
                fun main() {
                    var i: Int = 0;
                    while (i < 3) {
                        i = i + 1;
                    }
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
        assertNotNull(artifact.module)
    }

    @Test
    fun rejectsAssignmentToVal() {
        val artifact =
            frontend.compile(
                "assign_val.ck",
                """
                fun main() {
                    val i: Int = 0;
                    i = 1;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Cannot reassign") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsAssignmentToUnknownVariable() {
        val artifact =
            frontend.compile(
                "assign_unknown.ck",
                """
                fun main() {
                    nope = 1;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Unknown variable") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsAssignmentTypeMismatch() {
        val artifact =
            frontend.compile(
                "assign_type.ck",
                """
                fun main() {
                    var i: Int = 0;
                    i = "hello";
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Assignment type mismatch") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun compilesCompoundAssignmentToVar() {
        val artifact =
            frontend.compile(
                "compound.ck",
                """
                fun main() {
                    var i: Int = 0;
                    i += 1;
                    i -= 2;
                    i *= 3;
                    i /= 4;
                }
                """.trimIndent(),
            )

        assertTrue(
            artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun rejectsCompoundAssignmentToVal() {
        val artifact =
            frontend.compile(
                "compound_val.ck",
                """
                fun main() {
                    val i: Int = 0;
                    i += 1;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Cannot reassign") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun reportsIntLiteralOutOfRangeWithLongSuggestion() {
        val artifact =
            frontend.compile(
                "overflow.ck",
                """
                fun main() {
                    val n: Int = 2342343243;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any {
                it.message.contains("exceeds Int range") && it.message.contains("2342343243L")
            },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }

    @Test
    fun reportsLongLiteralOutOfRange() {
        val artifact =
            frontend.compile(
                "longoverflow.ck",
                """
                fun main() {
                    val n: Long = 99999999999999999999L;
                }
                """.trimIndent(),
            )

        assertEquals(null, artifact.module)
        assertTrue(
            artifact.analysis.diagnostics.any { it.message.contains("Long literal") && it.message.contains("out of range") },
            artifact.analysis.diagnostics.joinToString { it.message },
        )
    }
}
