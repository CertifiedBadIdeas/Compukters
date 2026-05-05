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

import ru.lazyhat.compukterkraft.lang.runtime.TextEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageFormatterTest {
    private val parser = DefaultParserFacade()
    private val formatter = LanguageFormatter()

    @Test
    fun parserPreservesLineAndBlockCommentsAsTrivia() {
        val source =
            """
            // leading file comment
            import terminal { println }; /* import note */

            /* before main */
            fun main() { // inline body
                println("hi");
            }
            """.trimIndent()

        val parsed = parser.parse("main.ck", source)

        assertEquals(emptyList(), parsed.syntaxDiagnostics.map { it.message })
        assertEquals(
            listOf(
                CommentKind.LINE,
                CommentKind.BLOCK,
                CommentKind.BLOCK,
                CommentKind.LINE,
            ),
            parsed.comments.map { it.kind },
        )
        assertTrue(parsed.comments[0].text.contains("leading file comment"))
        assertTrue(parsed.comments[1].text.contains("import note"))
        assertTrue(parsed.comments[2].text.contains("before main"))
        assertTrue(parsed.comments[3].text.contains("inline body"))
    }

    @Test
    fun formatReturnsNoEditsForSyntaxErrors() {
        val result = formatter.formatDocument("broken.ck", "fun main() { val x = ;")

        assertEquals(emptyList(), result.edits)
        assertTrue(result.diagnostics.any { it.message.contains("Cannot format source with syntax errors") })
    }

    @Test
    fun formatReturnsNoEditsWhenSourceIsAlreadyCanonical() {
        val source = "fun main() {\n    terminal::println(\"hi\")\n}\n"

        val result = formatter.formatDocument("main.ck", source)

        assertEquals(emptyList(), result.edits)
        assertEquals(false, result.changed)
    }

    @Test
    fun formatRemovesStatementAndImportSemicolons() {
        val source = "import terminal { println };\nfun main(){println(\"hi\");return;}"
        val expected =
            """
            import terminal { println }

            fun main() {
                println("hi")
                return
            }
            """.trimIndent() + "\n"

        val formatted = applySingleEdit(source, formatter.formatDocument("main.ck", source))

        assertEquals(expected, formatted)
    }

    @Test
    fun cleanupRemovesSelectiveImportUsedOnlyThroughFqn() {
        val source = "import terminal { println };\npub fun main(){terminal::println(\"hi\");}"
        val expected =
            """
            pub fun main() {
                terminal::println("hi")
            }
            """.trimIndent() + "\n"

        val cleaned = applySingleEdit(source, formatter.cleanupDocument("main.ck", source))

        assertEquals(expected, cleaned)
    }

    @Test
    fun formatsFunctionsStructsClassesAndControlFlow() {
        val source =
            """
            import terminal { println };
            struct Vec2{x:Int,y:Int}
            class Counter(var value:Int){init{this.value=this.value+1;}fun current():Int{return this.value;}static fun zero():Counter{return Counter(value=0);}}
            fun main(){val v:Vec2=Vec2(x=1,y=2);if(v.x>0){println("x="+v.x);}else{println("none");}while v.y>0 { return; }}
            """.trimIndent()

        val expected =
            """
            import terminal { println }

            struct Vec2 { x: Int, y: Int }

            class Counter(var value: Int) {
                init {
                    this.value = this.value + 1
                }

                fun current(): Int {
                    return this.value
                }

                static fun zero(): Counter {
                    return Counter(value = 0)
                }
            }

            fun main() {
                val v: Vec2 = Vec2(x = 1, y = 2)
                if (v.x > 0) {
                    println("x=" + v.x)
                } else {
                    println("none")
                }
                while v.y > 0 {
                    return
                }
            }
            """.trimIndent() + "\n"

        val result = formatter.formatDocument("main.ck", source)

        assertEquals(expected, applySingleEdit(source, result))
    }

    @Test
    fun formatsPubDeclarationsAndClassMembers() {
        val source =
            """
            pub struct Vec2{x:Int,y:Int}
            pub class Counter(pub var value:Int){pub val label:String="counter";pub fun current():Int{return this.value;}pub static fun zero():Counter{return Counter(value=0);}}
            pub fun main(){}
            """.trimIndent()

        val expected =
            """
            pub struct Vec2 { x: Int, y: Int }

            pub class Counter(pub var value: Int) {
                pub val label: String = "counter"

                pub fun current(): Int {
                    return this.value
                }

                pub static fun zero(): Counter {
                    return Counter(value = 0)
                }
            }

            pub fun main() {
            }
            """.trimIndent() + "\n"

        val result = formatter.formatDocument("main.ck", source)

        assertEquals(expected, applySingleEdit(source, result))
    }

    @Test
    fun formatIsIdempotent() {
        val source =
            """
            import terminal { println }

            fun main() {
                println("hi")
            }
            """.trimIndent() + "\n"

        val first = formatter.formatDocument("main.ck", source)
        val once = applyEdits(source, first.edits)
        val second = formatter.formatDocument("main.ck", once)

        assertEquals(source, once)
        assertEquals(emptyList(), second.edits)
    }

    @Test
    fun formatPreservesLeadingInlineAndBlockComments() {
        val source =
            """
            // file comment
            import terminal { println }; // import comment

            /* main comment */
            fun main(){
            // body comment
            println("hi"); /* call comment */
            }
            """.trimIndent()

        val formatted = applySingleEdit(source, formatter.formatDocument("main.ck", source))

        assertTrue(formatted.contains("// file comment"), formatted)
        assertTrue(formatted.contains("// import comment"), formatted)
        assertTrue(formatted.contains("/* main comment */"), formatted)
        assertTrue(formatted.contains("// body comment"), formatted)
        assertTrue(formatted.contains("/* call comment */"), formatted)
    }

    @Test
    fun formatSortsAndMergesSelectiveImports() {
        val source =
            """
            import "z.ck" { Zebra };
            import terminal { write, println };
            import "a.ck" { Beta };
            import "a.ck" { Alpha };
            pub fun main() { println("hi"); }
            """.trimIndent()

        val expected =
            """
            import "a.ck" { Alpha, Beta }
            import "z.ck" { Zebra }
            import terminal { println, write }

            pub fun main() {
                println("hi")
            }
            """.trimIndent() + "\n"

        val formatted = applySingleEdit(source, formatter.formatDocument("main.ck", source))

        assertEquals(expected, formatted)
    }

    @Test
    fun formatDoesNotRemoveUnusedImports() {
        val source =
            """
            import terminal { clear, println };
            pub fun main() { println("hi"); }
            """.trimIndent()

        val formatted = applySingleEdit(source, formatter.formatDocument("main.ck", source))

        assertTrue(formatted.contains("clear"), formatted)
    }

    @Test
    fun cleanupRemovesUnusedSelectiveImportItems() {
        val source =
            """
            import terminal { clear, println, write };
            pub fun main() { println("hi"); }
            """.trimIndent()

        val expected =
            """
            import terminal { println }

            pub fun main() {
                println("hi")
            }
            """.trimIndent() + "\n"

        val cleaned = applySingleEdit(source, formatter.cleanupDocument("main.ck", source))

        assertEquals(expected, cleaned)
    }

    @Test
    fun cleanupPreservesUsedFunctionStructAndClassImports() {
        val loader =
            MapSourceLoader(
                mapOf(
                    "main.ck" to
                        """
                        import "model.ck" { Counter, Vec2, make };
                        pub fun main() {
                            val v: Vec2 = make();
                            val c: Counter = Counter(value = v.x);
                            terminal::println("v=" + c.value);
                        }
                        """.trimIndent(),
                    "model.ck" to
                        """
                        pub struct Vec2 { x: Int, y: Int }
                        pub class Counter(pub var value: Int) {}
                        pub fun make(): Vec2 { return Vec2(x = 1, y = 2); }
                        """.trimIndent(),
                ),
            )
        val source = loader.read("main.ck")!!

        val cleaned = applySingleEdit(source, formatter.cleanupDocument("main.ck", source, loader))

        assertTrue(cleaned.contains("Counter"), cleaned)
        assertTrue(cleaned.contains("Vec2"), cleaned)
        assertTrue(cleaned.contains("make"), cleaned)
    }

    @Test
    fun cleanupReturnsNoEditsWhenAnalysisHasErrors() {
        val source =
            """
            import terminal { clear, println };
            pub fun main() { missing(); }
            """.trimIndent()

        val result = formatter.cleanupDocument("main.ck", source)

        assertEquals(emptyList(), result.edits)
    }

    private fun applySingleEdit(
        source: String,
        result: FormatResult,
    ): String {
        assertEquals(1, result.edits.size)
        return applyEdits(source, result.edits)
    }

    private fun applyEdits(
        source: String,
        edits: List<TextEdit>,
    ): String {
        var current = source
        edits.sortedByDescending { it.startOffset }.forEach { edit ->
            current = current.replaceRange(edit.startOffset, edit.endOffset, edit.replacement)
        }
        return current
    }
}
