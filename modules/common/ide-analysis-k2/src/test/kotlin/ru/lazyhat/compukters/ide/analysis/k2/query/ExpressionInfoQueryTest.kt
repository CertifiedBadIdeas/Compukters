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

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.DeclarationLocation
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExpressionInfoQueryTest {
    @Test
    fun `callback overloads resolve exact platform documentation and navigation`() {
        val source =
            """
            import kotlin.collections.*
            import kotlin.text.*
            fun main() {
                val list: List<Int> = listOf(1, 2)
                val iterable: Iterable<Int> = list
                list.lastOrNull { it > 0 }
                iterable.lastOrNull { it > 0 }
                list.lastOrNull()
                iterable.lastOrNull()
                list.map { listOf(it) }
                iterable.map { listOf(it) }
                buildString { append("a") }
                buildString(4) { append("b") }
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            for ((site, file, parameter) in listOf(
                Triple("list.lastOrNull {", "Lists.kt", "predicate"),
                Triple("iterable.lastOrNull {", "Iterables.kt", "predicate"),
                Triple("list.lastOrNull()", "Lists.kt", null),
                Triple("iterable.lastOrNull()", "Iterables.kt", null),
                Triple("list.map", "Iterables.kt", "transform"),
                Triple("iterable.map", "Iterables.kt", "transform"),
                Triple("buildString {", "StringBuilder.kt", "builderAction"),
                Triple("buildString(4)", "StringBuilder.kt", "builderAction"),
            )) {
                val offset = source.indexOf(site) + if ('.' in site) site.indexOf('.') + 1 else 0
                val hover =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                    ) as AnalysisResult.ExpressionInfo
                val info = assertNotNull(hover.value, site)
                val documentation = assertNotNull(info.documentation, "$site: $info")
                assertEquals("stdlib:core", assertIs<DeclarationOrigin.Platform>(info.origin, site).identity.name)
                if (parameter != null) assertTrue(documentation.contains("@param $parameter"), documentation)
                if (parameter == null) assertFalse(documentation.contains("@param predicate"), documentation)
                if (parameter == "predicate") assertTrue(assertNotNull(info.signature).contains("(T) -> Boolean"), info.signature)
                if (site == "buildString(4)") assertTrue(documentation.contains("@param capacity"), documentation)
                if (site == "buildString {") assertFalse(documentation.contains("@param capacity"), documentation)
                val navigation =
                    fixture.execute(
                        AnalysisQuery.Declaration(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                    ) as AnalysisResult.Declaration
                val target = assertIs<DeclarationLocation.Source>(navigation.locations.single(), site)
                assertTrue(target.path.value.endsWith("/$file"), "$site: $target")
            }
        }
    }

    @Test
    fun `list hover distinguishes mutable overloads read only contracts and selection functions`() {
        val source =
            "import kotlin.collections.*\n" +
                "fun main() { val values = ArrayList<Int>(2); val view: List<Int> = values; " +
                "values.add(0, 1); values.add(2); values.get(0); view.get(0); values.iterator(); " +
                "view.getOrNull(0); view.lastOrNull(); listOf(1, 2); emptyList<Int>() }"
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            for ((site, tags) in listOf(
                "ArrayList<Int>" to listOf("@param initialCapacity", "@constructor", "@throws IllegalArgumentException"),
                "add(0, 1)" to listOf("@param index", "@param element"),
                "add(2)" to listOf("@param element", "@return"),
                "get(0); view" to listOf("@param index", "@throws IllegalArgumentException"),
                "get(0); values.iterator" to listOf("@param index", "@return"),
                "iterator()" to listOf("@return"),
                "getOrNull" to listOf("@receiver", "@param index", "@return"),
                "lastOrNull" to listOf("@receiver", "@return"),
                "listOf(1" to listOf("@param elements", "@return"),
                "emptyList<Int>" to listOf("@return"),
            )) {
                val result =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), source.indexOf(site)),
                    ) as AnalysisResult.ExpressionInfo
                val info = assertNotNull(result.value, "$site: missing expression info")
                val documentation = assertNotNull(info.documentation, "$site: $info")
                tags.forEach { tag -> assertTrue(documentation.contains(tag), "$site: missing $tag in $documentation") }
                if (site == "add(2)") assertFalse(documentation.contains("@param index"), documentation)
                if (site == "get(0); values.iterator") assertFalse(documentation.contains("@throws"), documentation)
            }
        }
    }

    @Test
    fun `primitive hover resolves member constants and narrow extension documentation`() {
        val source =
            "import kotlin.experimental.inv\n" +
                "fun inspect(i: Int, f: Float, c: Char, u: UInt, b: Boolean, small: Byte) { " +
                "i.div(2); f.toInt(); c.code; u.shr(33); b.and(false); Float.MIN_VALUE; small.inv() }"
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            for ((name, tags) in listOf(
                "div" to listOf("@param other", "@return", "@throws ArithmeticException"),
                "toInt" to listOf("@return"),
                "code" to listOf("@see"),
                "shr" to listOf("@param bitCount", "@return"),
                "and" to listOf("@param other", "@return"),
                "MIN_VALUE" to emptyList(),
                "inv" to listOf("@receiver", "@return"),
            )) {
                val offset = source.lastIndexOf(name)
                val result =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                    ) as AnalysisResult.ExpressionInfo
                val info = assertNotNull(result.value, name)
                val documentation = assertNotNull(info.documentation, name)
                assertTrue(documentation.isNotBlank(), name)
                tags.forEach { tag -> assertTrue(documentation.contains(tag), "$name: missing $tag in $documentation") }
                val module = assertIs<DeclarationOrigin.Platform>(info.origin).identity.name
                assertEquals(if (name == "inv") "stdlib:core" else "kotlin:builtins", module)
            }
        }
    }

    @Test
    fun `hover signatures show Unit on both declaration and call`() {
        val source = "fun finish(value: Boolean) {}\nfun main() { finish(true) }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            for (offset in listOf(source.indexOf("finish"), source.lastIndexOf("finish"))) {
                val result =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                    ) as AnalysisResult.ExpressionInfo
                assertEquals("fun finish(value: Boolean): Unit", assertNotNull(result.value).signature)
            }
        }
    }

    @Test
    fun `hover uses short names in nested generic nullable and function types`() {
        val source = "package example.deep\nclass Box<T>\nfun convert(input: Box<String?>, fn: (Int) -> String): Box<String?> = input"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), source.indexOf("convert")),
                ) as AnalysisResult.ExpressionInfo
            val info = assertNotNull(result.value)
            assertEquals("Box<String?>", info.renderedType)
            val signature = assertNotNull(info.signature)
            assertTrue(signature.contains("Box<String?>"), signature)
            assertTrue(signature.contains("(Int) -> String"), signature)
            assertTrue(!signature.contains("example.deep.") && !signature.contains("kotlin."), signature)
        }
    }

    @Test
    fun `array copy hover resolves builtin documentation`() {
        val source = "fun main() { val values = IntArray(2); values.copyOf(3); values.copyInto(IntArray(2)) }"
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            for ((name, text) in listOf("copyOf" to "padding extra elements with zero", "copyInto" to "supporting overlapping ranges")) {
                val result =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), source.indexOf(name)),
                    ) as AnalysisResult.ExpressionInfo
                val value = assertNotNull(result.value)
                assertTrue(value.documentation.orEmpty().contains(text), value.toString())
                assertEquals("kotlin:builtins", assertIs<DeclarationOrigin.Platform>(value.origin).identity.name)
            }
        }
    }

    @Test
    fun `hover documentation follows resolved overload and declaration names`() {
        val source =
            """
            /** Integer documentation. */
            fun value(x: Int) = x
            /** String documentation.
             * @param x the text
             * @return the same text
             */
            fun value(x: String) = x
            /** Box documentation. */
            class Box
            fun main() { value(1); value("hello"); Box() }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            fun hover(offset: Int) =
                assertNotNull(
                    (
                        fixture.execute(
                            AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                        ) as AnalysisResult.ExpressionInfo
                    ).value,
                )
            assertEquals("Integer documentation.", hover(source.indexOf("fun value") + 4).documentation)
            assertEquals("Integer documentation.", hover(source.lastIndexOf("value(1)")).documentation)
            val string = assertNotNull(hover(source.lastIndexOf("value(\"hello\")")).documentation)
            assertTrue(string.startsWith("String documentation."))
            assertTrue(string.contains("@param x the text"))
            assertTrue(string.contains("@return the same text"))
            assertEquals("Box documentation.", hover(source.indexOf("class Box") + 6).documentation)
            assertEquals("Box documentation.", hover(source.lastIndexOf("Box()")).documentation)
            assertEquals(null, hover(source.indexOf("fun main") + 4).documentation)
        }
    }

    @Test
    fun `string stdlib hover resolves exact overload documentation`() {
        val source = "fun main() { \"hello\".trim(); \"hello\".split(\"\"); \"hello\".split(','); \"hello\".replace(\"l\", \"x\") }"
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            for ((offset, expected) in listOf(
                source.indexOf("trim") to "Removes leading and trailing whitespace.",
                source.indexOf("split") to "An empty delimiter separates UTF-16 code units",
                source.lastIndexOf("split") to "Zero limit means no limit",
                source.indexOf("replace") to "Replaces nonoverlapping oldValue matches",
            )) {
                val info =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                    ) as AnalysisResult.ExpressionInfo
                assertTrue(assertNotNull(info.value).documentation.orEmpty().contains(expected), "$expected: ${info.value}")
                val origin = assertIs<DeclarationOrigin.Platform>(assertNotNull(info.value).origin)
                assertEquals("stdlib:core", origin.identity.name)
            }
        }
    }

    @Test
    fun `hover documentation comes from exact attached platform source`() {
        val source = "fun main() { \"hello\".takeIf { true } }"
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            val info =
                assertNotNull(
                    (
                        fixture.execute(
                            AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), source.indexOf("takeIf")),
                        ) as AnalysisResult.ExpressionInfo
                    ).value,
                )
            val documentation = assertNotNull(info.documentation)
            assertTrue(documentation.startsWith("Returns this value when [predicate] is true, or null otherwise."))
            assertTrue(documentation.contains("@receiver "))
            assertTrue(documentation.contains("@param predicate "))
            assertTrue(documentation.contains("@return "))
        }
    }

    @Test
    fun `expression query renders the inferred type on a local declaration name`() {
        val source = "fun main() { val k = 2 }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val start = source.indexOf("k")
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        start,
                    ),
                ) as AnalysisResult.ExpressionInfo
            val info = assertNotNull(result.value)

            assertEquals("Int", info.renderedType)
            assertEquals(EditorRange(start, start + 1), info.range)
        }
    }

    @Test
    fun `expression query renders inferred Long arithmetic`() {
        val source = "fun main() { val total = 3_000_000_000L + 7 }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val start = source.indexOf("total")
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        start,
                    ),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("Long", assertNotNull(result.value).renderedType)
        }
    }

    @Test
    fun `expression query renders the explicit type on a local declaration name`() {
        val source = "fun main() { val message: String = \"ok\" }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val start = source.indexOf("message")
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        start,
                    ),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("String", assertNotNull(result.value).renderedType)
        }
    }

    @Test
    fun `expression query reports project origin`() {
        val source = "fun local() = Unit\nfun main() = local()"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.lastIndexOf("local") + 1,
                    ),
                ) as AnalysisResult.ExpressionInfo

            assertEquals(DeclarationOrigin.Project, assertNotNull(result.value).origin)
        }
    }

    @Test
    fun `expression query reports attached Guest API bundle origin`() {
        val source = "import compukter.terminal.Terminal\nfun main() = Terminal.write(\"ok\")"
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(
                        fixture.identity,
                        VirtualSourcePath.kotlin("main.kt"),
                        source.indexOf("write") + 1,
                    ),
                ) as AnalysisResult.ExpressionInfo

            val bundle = assertIs<DeclarationOrigin.Platform>(assertNotNull(result.value).origin)
            assertEquals("compukter:core", bundle.identity.name)
        }
    }

    @Test
    fun `expression query renders an inferred local type`() {
        val source = "fun main() { val answer = \"ok\"; println(answer) }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offset = source.lastIndexOf("answer") + 1
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("String", assertNotNull(result.value).renderedType)
        }
    }

    @Test
    fun `expression query renders a resolved callable signature`() {
        val source = "fun greet(name: String): String = name\nval result = greet(\"Ada\")"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offset = source.lastIndexOf("greet") + 1
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                ) as AnalysisResult.ExpressionInfo
            val info = assertNotNull(result.value)

            assertEquals("String", info.renderedType)
            assertTrue(assertNotNull(info.signature).contains("greet"), info.signature)
        }
    }

    @Test
    fun `expression query reports a smart cast type`() {
        val source = "fun length(value: Any): Int { if (value is String) return value.length; return 0 }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offset = source.lastIndexOf("value") + 1
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("String", assertNotNull(result.value).renderedType)
        }
    }

    @Test
    fun `expression query preserves the nominal type of an inline value class`() {
        val source =
            """
            @JvmInline
            value class Signal(val value: Int)

            fun main() {
                val signal = Signal(7)
                println(signal)
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offset = source.lastIndexOf("signal") + 1
            val result =
                fixture.execute(
                    AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                ) as AnalysisResult.ExpressionInfo

            assertEquals("Signal", assertNotNull(result.value).renderedType)
        }
    }
}
