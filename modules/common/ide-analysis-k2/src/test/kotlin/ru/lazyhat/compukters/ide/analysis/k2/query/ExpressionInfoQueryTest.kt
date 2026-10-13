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
import ru.lazyhat.compukters.ide.analysis.EditorExpressionInfo
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExpressionInfoQueryTest {
    @Test
    fun `project documentation preserves initial indented examples and hard breaks`() {
        val source =
            """
            /**
             *     val literal = 1
             *
             * First line.  #
             * Second line.
             * @return
             *     literal
             */
            fun sample(): Int = 1
            fun main() { sample() }
            """.trimIndent().replace("  #", "  ")
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            val result =
                assertIs<AnalysisResult.ExpressionInfo>(
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), source.lastIndexOf("sample()")),
                    ),
                )
            val documentation = assertNotNull(result.value?.documentation)
            assertTrue(documentation.startsWith("    val literal = 1\n"), documentation)
            assertTrue("First line.  \nSecond line." in documentation, documentation)
            val parsed =
                ru.lazyhat.compukters.ide.documentation.KDocDocument
                    .parse(documentation)
            assertEquals("    literal", parsed.tags.single().description)
        }
    }

    @Test
    fun `hash collection hover resolves constructors entries factories and defaults`() {
        val source =
            """
            import kotlin.collections.*
            fun main() {
                val map = HashMap<String, Int?>(4)
                val view: Map<String, Int?> = map
                map.put("a", 1)
                map.get("a")
                view.get("a")
                map.keys
                map.values
                map.entries
                val entry = map.entries.iterator().next() as MutableMap.MutableEntry<String, Int?>
                entry.setValue(2)
                entry.component1()
                entry.component2()
                map.set("a", 3)
                map.getOrPut("b") { null }
                val set = HashSet<String>(4)
                set.add("a")
                set.iterator()
                emptyMap<String, Int>()
                mapOf("a" to 1)
                mutableMapOf("b" to 2)
                emptySet<String>()
                setOf("a", "a")
                mutableSetOf("b")
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            for ((site, tags) in listOf(
                "HashMap<String" to listOf("@param initialCapacity", "@constructor", "@throws IllegalArgumentException"),
                "put(\"a\"" to listOf("@param key", "@param value", "@return", "@throws IllegalArgumentException"),
                "get(\"a\")" to listOf("@param key", "@return"),
                "view.get" to listOf("@param key", "@return"),
                "map.keys" to emptyList(),
                "map.values" to emptyList(),
                "map.entries" to emptyList(),
                "setValue(2)" to listOf("@param newValue", "@return"),
                "component1" to listOf("@receiver", "@return"),
                "component2" to listOf("@receiver", "@return"),
                "set(\"a\"" to listOf("@receiver", "@param key", "@param value"),
                "getOrPut" to listOf("@receiver", "@param key", "@param defaultValue", "@return"),
                "HashSet<String" to listOf("@param initialCapacity", "@constructor", "@throws IllegalArgumentException"),
                "add(\"a\")" to listOf("@param element", "@return"),
                "set.iterator" to listOf("@return"),
                "emptyMap<String" to listOf("@param K", "@param V", "@return"),
                "mapOf(\"a\"" to listOf("@param pairs", "@return"),
                "mutableMapOf" to listOf("@param pairs", "@return"),
                "emptySet<String" to listOf("@param T", "@return"),
                "setOf(\"a\"" to listOf("@param elements", "@return"),
                "mutableSetOf" to listOf("@param elements", "@return"),
            )) {
                val offset = source.indexOf(site) + if ('.' in site) site.indexOf('.') + 1 else 0
                val result =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                    ) as AnalysisResult.ExpressionInfo
                val info = assertNotNull(result.value, site)
                val documentation = assertNotNull(info.documentation, "$site: $info")
                tags.forEach { tag -> assertTrue(documentation.contains(tag), "$site: missing $tag in $documentation") }
                if (site == "set(\"a\"") {
                    assertTrue(assertNotNull(info.signature).endsWith(": Unit"), info.signature)
                    assertFalse(documentation.contains("@return"), documentation)
                }
            }
        }
    }

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
    fun `range hover resolves progression bounds interfaces and widened operators`() {
        val families = listOf("Int", "Long", "Char", "UInt", "ULong")
        val source =
            buildString {
                appendLine("import kotlin.ranges.*")
                appendLine("fun main() {")
                for ((index, type) in families.withIndex()) {
                    val first = if (type == "Char") "'a'" else "3.to$type()"
                    val last = if (type == "Char") "'f'" else "7.to$type()"
                    val step = if (type == "Long" || type == "ULong") "2L" else "2"
                    appendLine("val range$index = ${type}Range($first, $last)")
                    appendLine("range$index.start; range$index.endInclusive; range$index.endExclusive")
                    appendLine("range$index.contains($first); range$index.isEmpty()")
                    appendLine("val progression$index = ${type}Progression($first, $last, $step)")
                    appendLine("progression$index.first; progression$index.last; progression$index.step")
                    appendLine("progression$index.isEmpty(); progression$index.iterator(); progression$index.step($step)")
                }
                for (type in listOf("Byte", "Short", "Int", "Long", "UByte", "UShort", "UInt", "ULong")) {
                    appendLine("val value$type: $type = 3.to$type()")
                }
                appendLine("val valueChar = 'a'")
                for (group in listOf(listOf("Byte", "Short", "Int", "Long"), listOf("UByte", "UShort", "UInt", "ULong"), listOf("Char"))) {
                    for (left in group) {
                        for (right in group) {
                            for (operation in listOf("rangeTo", "rangeUntil", "until", "downTo")) {
                                appendLine("value$left.$operation(value$right)")
                            }
                        }
                    }
                }
                appendLine("}")
                appendLine("fun bounds(closed: ClosedRange<Int>, open: OpenEndRange<Int>, floating: ClosedFloatingPointRange<Float>) {")
                appendLine("closed.start; closed.endInclusive; closed.contains(4); closed.isEmpty()")
                appendLine("open.start; open.endExclusive; open.contains(4); open.isEmpty()")
                appendLine("1.0f.rangeTo(2.0f)")
                appendLine("floating.lessThanOrEquals(1.0f, 2.0f)")
                appendLine("1.0f.rangeUntil(2.0f); 1.0.rangeTo(2.0); 1.0.rangeUntil(2.0)")
                appendLine("false.rangeTo(true); false.rangeUntil(true)")
                appendLine("}")
            }
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            fun documented(
                site: String,
                symbol: String,
                vararg tags: String,
            ) {
                val start = source.indexOf(site)
                assertTrue(start >= 0, site)
                val offset = start + site.indexOf(symbol)
                val result =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                    ) as AnalysisResult.ExpressionInfo
                val info = assertNotNull(result.value, site)
                assertEquals("stdlib:core", assertIs<DeclarationOrigin.Platform>(info.origin, "$site: $info").identity.name)
                val documentation = assertNotNull(info.documentation, "$site: $info")
                tags.forEach { tag -> assertTrue(documentation.contains(tag), "$site: missing $tag in $documentation") }
            }
            for ((index, type) in families.withIndex()) {
                documented("${type}Range(", "${type}Range", "@constructor", "@param first", "@param last")
                documented(
                    "${type}Progression(",
                    "${type}Progression",
                    "@param endInclusive",
                    "@param step",
                    "@throws IllegalArgumentException",
                )
                for (property in listOf("start", "endInclusive")) documented("range$index.$property", property)
                documented("range$index.endExclusive", "endExclusive", "@throws IllegalStateException")
                documented("range$index.contains", "contains", "@param value", "@return")
                documented("range$index.isEmpty", "isEmpty", "@return")
                for (property in listOf("first", "last", "step")) documented("progression$index.$property", property)
                documented("progression$index.isEmpty", "isEmpty", "@return")
                documented("progression$index.iterator", "iterator", "@return", "NoSuchElementException")
                documented("progression$index.step(", "step", "@receiver", "@param step", "@throws IllegalArgumentException")
            }
            for (group in listOf(listOf("Byte", "Short", "Int", "Long"), listOf("UByte", "UShort", "UInt", "ULong"), listOf("Char"))) {
                for (left in group) {
                    for (right in group) {
                        for (operation in listOf("rangeTo", "rangeUntil", "until", "downTo")) {
                            documented("value$left.$operation(value$right)", operation, "@receiver", "@param other", "@return")
                        }
                    }
                }
            }
            for ((receiver, bound) in listOf("closed" to "endInclusive", "open" to "endExclusive")) {
                documented("$receiver.start", "start")
                documented("$receiver.$bound", bound)
                documented("$receiver.contains", "contains", "@param value", "@return")
                documented("$receiver.isEmpty", "isEmpty", "@return")
            }
            documented("floating.lessThanOrEquals", "lessThanOrEquals", "@param left", "@param right", "@return", "NaN")
            for (site in listOf("1.0f.rangeTo", "1.0f.rangeUntil", "1.0.rangeTo", "1.0.rangeUntil")) {
                documented(site, site.substringAfterLast('.'), "@param other", "@return", "NaN")
            }
            for (operation in listOf("rangeTo", "rangeUntil")) {
                documented("false.$operation", operation, "@param other", "@return", "false < true")
            }
        }
    }

    @Test
    fun `array hover resolves every family and exact copy overloads`() {
        val families =
            listOf(
                "Boolean",
                "Byte",
                "Char",
                "Short",
                "Int",
                "Long",
                "Float",
                "Double",
                "UByte",
                "UShort",
                "UInt",
                "ULong",
            )
        val source =
            buildString {
                appendLine("import kotlin.collections.*")
                appendLine("fun main() {")
                for ((index, type) in families.withIndex()) {
                    appendLine("val values$index = ${type}Array(2)")
                    appendLine("${type.lowercase()}ArrayOf()")
                    appendLine("values$index.size")
                    appendLine("values$index.get(0)")
                    appendLine("values$index.set(0, values$index.get(1))")
                    appendLine("values$index.copyOf()")
                    appendLine("values$index.copyOf(3)")
                    appendLine("values$index.copyInto(values$index, 1, 0, 1)")
                }
                appendLine("val strings = Array<String>(2) { \"item\" }")
                appendLine("arrayOf(\"a\", \"b\")")
                appendLine("arrayOfNulls<String>(2)")
                appendLine("strings.size")
                appendLine("strings.get(0)")
                appendLine("strings.set(0, \"updated\")")
                appendLine("strings.copyOf()")
                appendLine("strings.copyOf(3)")
                appendLine("strings.copyInto(strings, 1, 0, 1)")
                appendLine("strings.copyOfRange(0, 1)")
                appendLine("}")
            }
        K2QueryFixture.sourceWithGuestApi(true, "main.kt" to source).use { fixture ->
            fun hover(site: String): EditorExpressionInfo {
                val start = source.indexOf(site)
                assertTrue(start >= 0, site)
                val offset = start + if ('.' in site) site.indexOf('.') + 1 else 0
                val result =
                    fixture.execute(
                        AnalysisQuery.ExpressionInfo(fixture.identity, VirtualSourcePath.kotlin("main.kt"), offset),
                    ) as AnalysisResult.ExpressionInfo
                val info = assertNotNull(result.value, site)
                assertEquals("kotlin:builtins", assertIs<DeclarationOrigin.Platform>(info.origin).identity.name)
                assertNotNull(info.documentation, site)
                return info
            }

            fun documented(
                site: String,
                vararg tags: String,
            ) {
                val documentation = assertNotNull(hover(site).documentation)
                tags.forEach { tag -> assertTrue(documentation.contains(tag), "$site: missing $tag in $documentation") }
            }
            for ((index, type) in families.withIndex()) {
                documented("${type}Array(2)")
                documented("${type.lowercase()}ArrayOf()", "@param elements", "@return")
                documented("values$index.size")
                documented("values$index.get(0)", "@param index", "@return", "@throws IndexOutOfBoundsException")
                documented("values$index.set", "@param index", "@param value", "@throws IndexOutOfBoundsException")
                val unchanged = assertNotNull(hover("values$index.copyOf()").documentation)
                assertFalse(unchanged.contains("@param newSize"), unchanged)
                documented("values$index.copyOf(3)", "@param newSize", "@throws NegativeArraySizeException")
                documented("values$index.copyInto", "@param destinationOffset", "@param startIndex", "@param endIndex", "@return")
            }
            documented("Array<String>", "@param T")
            documented("arrayOf(", "@param T", "@param elements", "@return")
            documented("arrayOfNulls", "@param size", "@throws NegativeArraySizeException")
            documented("strings.size")
            documented("strings.get(0)", "@param index", "@return", "@throws IndexOutOfBoundsException")
            documented("strings.set", "@param index", "@param value", "@throws IndexOutOfBoundsException")
            documented("strings.copyOf()", "@param T", "@return")
            val resized = hover("strings.copyOf(3)")
            assertTrue(assertNotNull(resized.signature).endsWith(": Array<T?>"), resized.signature)
            documented("strings.copyOf(3)", "@param newSize", "padding extra elements with null")
            documented("strings.copyInto", "@param T", "@param destination", "@throws IndexOutOfBoundsException")
            documented("strings.copyOfRange", "@param fromIndex", "@param toIndex", "Array<String>", "@throws NegativeArraySizeException")
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
