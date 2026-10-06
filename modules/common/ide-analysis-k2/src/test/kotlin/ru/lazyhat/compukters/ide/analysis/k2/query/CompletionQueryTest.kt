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

import ru.lazyhat.compukters.addon.api.AddonGuestApiBundleCodec
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisQuery
import ru.lazyhat.compukters.ide.analysis.AnalysisResult
import ru.lazyhat.compukters.ide.analysis.CompletionCallShape
import ru.lazyhat.compukters.ide.analysis.CompletionCallablePresentation
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorEditResult
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.editor.EditorTextEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompletionQueryTest {
    @Test
    fun `completion distinguishes provider values from imported and unimported device types`() {
        for ((qualified, name) in listOf(
            "compukter.display.TextDisplay" to "TextDisplay",
            "fixture.kinetics.AddonPeripheral" to "AddonPeripheral",
        )) {
            for (imported in listOf(false, true)) {
                for (typePosition in listOf(false, true)) {
                    val prefix = name.dropLast(2)
                    val source =
                        (if (imported) "import $qualified\n" else "") +
                            if (typePosition) "fun use(device: $prefix) {}" else "fun main() { $prefix }"
                    K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
                        val item = fixture.complete("main.kt", source.lastIndexOf(prefix) + prefix.length).items.single { it.label == name }
                        assertEquals(if (typePosition) CompletionKind.Class else CompletionKind.PeripheralProvider, item.kind, source)
                    }
                }
            }
        }
    }

    @Test
    fun `array copying completes with specialized return types`() {
        for (attachedSources in listOf(false, true)) {
            for ((initializer, expected) in listOf(
                "IntArray(2)" to "IntArray",
                "CharArray(2)" to "CharArray",
                "arrayOf(\"a\")" to "Array<String>",
            )) {
                val source = "fun main() { val values = $initializer; values. }"
                K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to source).use { fixture ->
                    val items = fixture.complete("main.kt", source.indexOf("values.") + 7).items
                    val copies = items.filter { it.insertText == "copyOf" }
                    assertEquals(2, copies.size, expected)
                    val returns = copies.map { it.callablePresentation?.returnType }.toSet()
                    assertEquals(if (expected == "Array<String>") setOf(expected, "Array<String?>") else setOf(expected), returns)
                    assertEquals(expected, items.single { it.insertText == "copyInto" }.callablePresentation?.returnType)
                }
            }
        }
    }

    @Test
    fun `string stdlib helpers complete with overloads and concrete return types`() {
        for (attachedSources in listOf(false, true)) {
            val source = "fun main() { val text = \"hello\"; text. }"
            K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to source).use { fixture ->
                val items = fixture.complete("main.kt", source.indexOf("text.") + 5).items
                for (name in listOf("trim", "trimStart", "trimEnd", "substringBeforeLast", "removeSuffix", "replace")) {
                    val matches = items.filter { it.insertText == name }
                    assertTrue(matches.isNotEmpty(), name)
                    assertTrue(matches.all { it.callablePresentation?.returnType == "String" }, name)
                }
                for (name in listOf("isBlank", "isEmpty", "isNotBlank", "isNotEmpty")) {
                    assertEquals("Boolean", items.single { it.insertText == name }.callablePresentation?.returnType)
                }
                assertEquals(2, items.count { it.insertText == "split" })
                assertEquals(2, items.count { it.insertText == "replace" })
                assertTrue(
                    items
                        .filter { it.insertText == "split" || it.insertText == "lines" }
                        .all { it.callablePresentation?.returnType == "List<String>" },
                )
                assertTrue(items.filter { it.insertText == "split" }.any { it.label.contains("Char") })
                assertTrue(items.filter { it.insertText == "split" }.any { it.label.contains("String") })
            }
        }
    }

    @Test
    fun `completion allows trimmed word fragments without gaps inside a word`() {
        for (prefix in listOf("ti", "tf", "ake", "tke")) {
            val source = "fun takeIf() = Unit\nfun main() { $prefix }"
            K2QueryFixture.source("main.kt" to source).use { fixture ->
                val items = fixture.complete("main.kt", source.lastIndexOf(prefix) + prefix.length).items
                assertEquals(prefix != "tke", items.any { it.insertText == "takeIf" }, prefix)
                if (prefix != "tke") {
                    assertEquals(
                        CompletionNameMatcher(prefix).ranges("takeIf"),
                        items.single { it.insertText == "takeIf" }.matchedNameRanges,
                    )
                }
            }
        }
        val source = "fun main() { ake }"
        K2QueryFixture.source("main.kt" to source, "lib.kt" to "package library\nclass TakeIf").use { fixture ->
            val imported = fixture.complete("main.kt", source.indexOf("ake") + 3).items.single { it.insertText == "TakeIf" }
            assertTrue(imported.additionalEdits.any { it.text.contains("import library.TakeIf") })
            assertEquals(listOf(EditorRange(1, 4)), imported.matchedNameRanges)
        }
    }

    @Test
    fun `camel completion matches word prefixes but not arbitrary skipped letters`() {
        fun sourceFor(prefix: String) = "fun emptyList() = Unit\nfun emptyMap() = Unit\nfun main() { $prefix }"
        var source = sourceFor("em")
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val prefix = fixture.complete("main.kt", source.lastIndexOf("em") + 2).items.map { it.insertText }
            assertTrue(prefix.indexOf("emptyList") >= 0)
            assertTrue(prefix.indexOf("emptyList") < prefix.indexOf("emptyMap"))
            source = sourceFor("emm")
            fixture.update("main.kt" to source)
            val camel = fixture.complete("main.kt", source.indexOf("emm") + 3).items.map { it.insertText }
            assertTrue("emptyMap" in camel)
            assertTrue("emptyList" !in camel)
            source = sourceFor("emty")
            fixture.update("main.kt" to source)
            assertTrue(fixture.complete("main.kt", source.indexOf("emty") + 4).items.isEmpty())
        }
    }

    @Test
    fun `camel completion also finds admitted stdlib declarations`() {
        val source = "fun main() { el }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            assertTrue(fixture.complete("main.kt", source.indexOf("el") + 2).items.any { it.insertText == "emptyList" })
        }
    }

    @Test
    fun `camel completion covers members locals and autoimports with direct prefixes first`() {
        val source =
            """
            class Holder {
                fun emptyZoo() = Unit
                fun emzDirect() = Unit
            }
            fun main() {
                val emptyLocalMap = 1
                elm
                val holder = Holder()
                holder.emz
                abz
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source, "lib.kt" to "package library\nclass AlphaBetaZoo").use { fixture ->
            val local = fixture.complete("main.kt", source.indexOf("elm") + 3).items
            assertTrue(local.any { it.insertText == "emptyLocalMap" })
            val members = fixture.complete("main.kt", source.indexOf("holder.emz") + "holder.emz".length).items.map { it.insertText }
            assertEquals(listOf("emzDirect", "emptyZoo"), members)
            val imported = fixture.complete("main.kt", source.indexOf("abz") + 3).items.single { it.insertText == "AlphaBetaZoo" }
            assertTrue(imported.additionalEdits.any { it.text.contains("import library.AlphaBetaZoo") })
        }
    }

    @Test
    fun `scope functions specialize their result and lambda from the explicit receiver`() {
        val source = "fun main() { val text = \"hello\"; text.also; text.apply }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            for (name in listOf("also", "apply")) {
                for (prefixLength in listOf(0, 1, name.length)) {
                    val offset = source.indexOf("text.$name") + "text.".length + prefixLength
                    val item = fixture.complete("main.kt", offset).items.single { it.insertText == name }
                    assertEquals("String", item.callablePresentation?.returnType, "$name prefix=$prefixLength")
                    assertTrue(item.label.contains("String"), item.label)
                }
            }
            for ((incomplete, marker, expected) in listOf(
                Triple("fun main() { val text = \"hello\"; text. }", "text.", "String"),
                Triple("fun main() { val text: String? = null; text. }", "text.", "String?"),
                Triple("class Box<T>(val value: T)\nfun make() = Box(1)\nfun main() { make(). }", "make().", "Box<Int>"),
            )) {
                fixture.update("main.kt" to incomplete)
                val items = fixture.complete("main.kt", incomplete.indexOf(marker) + marker.length).items
                for (name in listOf("also", "apply")) {
                    val item = items.single { it.insertText == name }
                    assertEquals(expected, item.callablePresentation?.returnType, "$name after $marker")
                    assertTrue(item.label.contains(expected), item.label)
                }
            }
            val safeCall = "fun main() { val text: String? = null; text?. }"
            fixture.update("main.kt" to safeCall)
            val safeItems = fixture.complete("main.kt", safeCall.indexOf("text?.") + 6).items
            for (name in listOf("also", "apply")) {
                assertEquals("String", safeItems.single { it.insertText == name }.callablePresentation?.returnType)
            }
        }
    }

    @Test
    fun `literal and chained receivers specialize extensions without inferring independent lambda results`() {
        for ((receiver, expected) in listOf(
            "\"hello\"" to "String",
            "(42)" to "Int",
            "Box(Box(1))" to "Box<Box<Int>>",
            "Box(1).also { }" to "Box<Int>",
            "Box(1).apply { }" to "Box<Int>",
        )) {
            val source = "class Box<T>(val value: T)\nfun main() { $receiver. }"
            K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
                val items = fixture.complete("main.kt", source.indexOf(". }") + 1).items
                for (name in listOf("also", "apply")) {
                    val item = items.single { it.insertText == name }
                    assertEquals(expected, item.callablePresentation?.returnType, receiver)
                    assertTrue(item.label.contains(expected), item.label)
                }
                val let = items.single { it.insertText == "let" }
                assertEquals("R", let.callablePresentation?.returnType)
                assertTrue(let.label.contains("($expected) -> R"), let.label)
            }
        }
    }

    @Test
    fun `completion fragment keeps lexical receivers shadowing and snapshot state`() {
        val source =
            """
            class Box<T>(val value: T)
            class Scope {
                val value = "outer"
                fun <T> Box<T>.scopedResult(): T = value
                fun work() {
                    val box = Box(1)
                    box.scopedR
                    val value = 2
                    value.
                }
            }
            """.trimIndent()
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val identity = fixture.identity
            val snapshot = fixture.snapshot
            repeat(2) {
                val scoped = fixture.complete("main.kt", source.indexOf("box.scopedR") + "box.scopedR".length)
                assertEquals(
                    "Int",
                    scoped.items
                        .single { it.insertText == "scopedResult" }
                        .callablePresentation
                        ?.returnType,
                )
                val local = fixture.complete("main.kt", source.indexOf("value.\n") + "value.".length)
                assertEquals(
                    "Int",
                    local.items
                        .single { it.insertText == "also" }
                        .callablePresentation
                        ?.returnType,
                )
                assertEquals(identity, fixture.identity)
                assertEquals(source, snapshot.files.getValue(VirtualSourcePath.kotlin("main.kt")).text)
            }
        }
    }

    @Test
    fun `completion preserves specialized member and extension signatures with declared receiver context`() {
        val source =
            """
            package sample
            open class Box<T>(val value: T) {
                fun result(): T = value
                fun consume(value: T) {}
            }
            class IntBox: Box<Int>(1) {
                fun inside() { extensionR }
            }
            fun <T> Box<T>.extensionResult(): T = value
            fun <T> Box<T>.lambdaResult(block: (entry: T) -> T): T = block(value)
            fun <T> T.identityResult(): T = this
            fun <R> unresolvedResult(value: R): R = value
            fun main() {
                val box = Box(1)
                val inherited = IntBox()
                box.resu
                box.cons
                inherited.resu
                box.extensionR
                box.lambdaR
                1.identityR
                unresolvedR
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            fun item(
                marker: String,
                name: String,
            ) = fixture.complete("main.kt", source.indexOf(marker) + marker.length).items.single { it.insertText == name }
            for (marker in listOf("box.resu", "inherited.resu")) {
                assertEquals(CompletionCallablePresentation(null, "sample", "Int"), item(marker, "result").callablePresentation)
            }
            assertEquals("consume(value: Int)", item("box.cons", "consume").label)
            assertEquals(CompletionKind.MemberFunction, item("box.cons", "consume").kind)
            assertEquals(
                CompletionCallablePresentation("Box<T>", "sample", "Int"),
                item("box.extensionR", "extensionResult").callablePresentation,
            )
            assertEquals(
                CompletionCallablePresentation("Box<T>", "sample", "Int"),
                item("inside() { extensionR", "extensionResult").callablePresentation,
            )
            assertEquals("lambdaResult { block: (Int) -> Int }", item("box.lambdaR", "lambdaResult").label)
            assertEquals(CompletionCallablePresentation("T", "sample", "Int"), item("1.identityR", "identityResult").callablePresentation)
            assertEquals(
                CompletionCallablePresentation(null, "sample", "R"),
                item("unresolvedR\n", "unresolvedResult").callablePresentation,
            )
        }
    }

    @Test
    fun `completion labels single lambda arguments with braces and unnamed function types`() {
        val source =
            """
            typealias Action = (entry: Int) -> Unit
            fun dslEmpty(block: () -> Unit) {}
            fun dslNamed(block: (entry: Int, text: String) -> Unit) {}
            fun dslAlias(block: Action) {}
            fun dslReceiver(block: String.(entry: Int) -> Unit) {}
            fun dslNested(block: (next: (entry: Int) -> Unit) -> String) {}
            fun dslNullable(block: ((entry: Int) -> Unit)?) {}
            fun dslVararg(vararg blocks: (entry: Int) -> Unit) {}
            fun dslMultiple(value: Int, block: (entry: Int) -> Unit) {}
            fun main() { dsl }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val items = fixture.complete("main.kt", source.lastIndexOf("dsl") + 3).items.associateBy { it.insertText }
            for ((name, type) in mapOf(
                "dslEmpty" to "() -> Unit",
                "dslNamed" to "(Int, String) -> Unit",
                "dslAlias" to "(Int) -> Unit",
                "dslReceiver" to "String.(Int) -> Unit",
                "dslNested" to "((Int) -> Unit) -> String",
            )) {
                val item = requireNotNull(items[name])
                assertEquals("$name { block: $type }", item.label)
                assertEquals(CompletionCallShape(true, false, true), item.callShape)
            }
            for (name in listOf("dslNullable", "dslVararg", "dslMultiple")) {
                assertTrue(requireNotNull(items[name]).label.startsWith("$name("))
            }
        }
    }

    @Test
    fun `autoimported lambda function labels use the same brace presentation`() {
        val source = "package app\nfun main() { remote }"
        K2QueryFixture
            .source(
                "main.kt" to source,
                "lib.kt" to "package library\nfun remoteCall(block: (entry: Int) -> Unit) {}",
            ).use { fixture ->
                val item = fixture.complete("main.kt", source.indexOf("remote") + 6).items.single { it.insertText == "remoteCall" }
                assertEquals("remoteCall { block: (Int) -> Unit }", item.label)
                assertTrue(item.additionalEdits.isNotEmpty())
                assertEquals(CompletionCallShape(true, false, true), item.callShape)
            }
    }

    @Test
    fun `automatic completion suppresses function declaration names`() {
        val declarations =
            listOf(
                "fun pri",
                "fun pri(value: Int) {}",
                "class Host { fun pri() {} }",
                "fun outer() { fun pri() {} }",
                "fun Int.pri() {}",
                "fun <T> T.pri(value: T) {}",
                "fun `pri`() {}",
            )
        val sources = declarations.mapIndexed { index, declaration -> "case$index.kt" to declaration }
        K2QueryFixture.source(*sources.toTypedArray()).use { fixture ->
            for ((path, source) in sources) {
                val start = source.indexOf("pri")
                for (length in 1..3) {
                    val result = fixture.complete(path, start + length)
                    assertTrue(result.items.isEmpty(), "$source at $length: ${result.items}")
                    assertEquals(EditorRange(start, start + length), result.replacement)
                }
            }
        }
    }

    @Test
    fun `function declarations retain completion in types bodies and explicit requests`() {
        val source = "class PrefixType\nfun printTarget() {}\nfun pri(value: Pre): Pre { printT }\nfun Pre.extension() {}"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            for (start in listOf(source.indexOf("Pre)"), source.indexOf("Pre {"), source.indexOf("Pre.extension"))) {
                assertTrue(fixture.complete("main.kt", start + 3).items.any { it.insertText == "PrefixType" })
            }
            assertTrue(fixture.complete("main.kt", source.indexOf("printT }") + 6).items.any { it.insertText == "printTarget" })
            assertTrue(
                fixture.complete("main.kt", source.indexOf("fun pri(") + 7, CompletionTrigger.Manual).items.any {
                    it.insertText == "printTarget"
                },
            )
        }
    }

    @Test
    fun `completion derives call shapes from resolved function parameters`() {
        val source =
            """
            typealias Block = () -> Unit
            fun testEmpty() {}
            fun testValue(value: Int) {}
            fun testLambda(block: Block) {}
            fun testDefault(value: Int = 1, block: Block) {}
            fun testVararg(vararg values: Int, block: Block) {}
            fun testRequired(value: Int, block: Block) {}
            val testProperty = 1
            fun main() { test }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val items = fixture.complete("main.kt", source.lastIndexOf("test") + 4).items.associateBy { it.insertText }

            fun shape(name: String) = requireNotNull(items[name]).callShape
            assertEquals(
                CompletionCallShape(false, false, false),
                shape("testEmpty"),
            )
            assertEquals(
                CompletionCallShape(true, true, false),
                shape("testValue"),
            )
            assertEquals(
                CompletionCallShape(true, false, true),
                shape("testLambda"),
            )
            assertEquals(shape("testLambda"), shape("testDefault"))
            assertEquals(shape("testLambda"), shape("testVararg"))
            assertEquals(
                CompletionCallShape(true, true, true),
                shape("testRequired"),
            )
            assertEquals(null, shape("testProperty"))
        }
    }

    @Test
    fun `autoimported project functions retain semantic call shapes`() {
        val source = "package app\nfun main() { remote }"
        K2QueryFixture
            .source(
                "main.kt" to source,
                "lib.kt" to "package library\nfun remoteCall(value: Int = 1, block: () -> Unit) {}",
            ).use { fixture ->
                val item = fixture.complete("main.kt", source.indexOf("remote") + 6).items.single { it.insertText == "remoteCall" }
                assertEquals(
                    CompletionCallShape(true, false, true),
                    item.callShape,
                )
                assertTrue(item.additionalEdits.isNotEmpty())
            }
    }

    @Test
    fun `call references imports and shorthand interpolation complete names without call shapes`() {
        val source = "import referenceT\nfun referenceTarget() {}\nfun main() { val ref = ::referenceT; val text = \"\$referenceT\" }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            for (offset in listOf(
                source.indexOf("::referenceT") + "::referenceT".length,
                source.indexOf("\$referenceT") + "\$referenceT".length,
                source.indexOf("import referenceT") + "import referenceT".length,
            )) {
                assertEquals(
                    null,
                    fixture
                        .complete("main.kt", offset)
                        .items
                        .first { it.insertText == "referenceTarget" }
                        .callShape,
                )
            }
        }
    }

    @Test
    fun `block interpolation permits calls but an empty shorthand template does not`() {
        for ((template, expected) in listOf("\${referenceT}" to CompletionCallShape(false, false, false), "\$" to null)) {
            val source = "fun referenceTarget() {}\nfun main() { val text = \"$template\" }"
            val offset = source.indexOf(template) + template.length - if (template.endsWith('}')) 1 else 0
            K2QueryFixture.source("main.kt" to source).use { fixture ->
                assertEquals(
                    expected,
                    fixture
                        .complete("main.kt", offset)
                        .items
                        .first { it.insertText == "referenceTarget" }
                        .callShape,
                )
            }
        }
    }

    @Test
    fun `completion finds a reference member through a nullable safe call`() {
        val source = "class Node(val name: String)\nfun main() { val node: Node? = null; node?.na }"
        val prefixStart = source.lastIndexOf("na")
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", prefixStart + 2)

            assertEquals(EditorRange(prefixStart, prefixStart + 2), result.replacement)
            assertTrue(result.items.any { it.insertText == "name" }, result.items.toString())
        }
    }

    @Test
    fun `completion proposes keywords for declarations and executable blocks`() {
        val source =
            """
            pac
            fu
            class Host {
                ov
                fun run() { val variable = 1; va; ret }
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val packageDirective = fixture.complete("main.kt", source.indexOf("pac") + 3)
            val topLevel = fixture.complete("main.kt", source.indexOf("fu") + 2)
            val member = fixture.complete("main.kt", source.indexOf("ov") + 2)
            val local = fixture.complete("main.kt", source.indexOf("va;") + 2)
            val block = fixture.complete("main.kt", source.indexOf("ret") + 3)

            assertEquals(CompletionKind.Keyword, packageDirective.items.single { it.insertText == "package" }.kind)
            assertEquals(CompletionKind.Keyword, topLevel.items.single { it.insertText == "fun" }.kind)
            assertEquals(CompletionKind.Keyword, member.items.single { it.insertText == "override" }.kind)
            assertEquals(listOf("val", "var"), local.items.take(2).map { it.insertText })
            assertTrue(local.items.any { it.insertText == "variable" }, local.items.toString())
            assertEquals(CompletionKind.Keyword, block.items.single { it.insertText == "return" }.kind)
        }
    }

    @Test
    fun `completion does not propose suspend outside the Guest subset`() {
        val source = "sus"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", source.length)

            assertTrue(result.items.none { it.insertText == "suspend" }, result.items.toString())
        }
    }

    @Test
    fun `completion suppresses keywords outside unqualified Kotlin code`() {
        val source =
            """
            import ret
            fun main() {
                "ret"
                // ret
                "text".ret
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offsets =
                listOf(
                    source.indexOf("ret") + 3,
                    source.indexOf("ret", source.indexOf('"')) + 3,
                    source.indexOf("ret", source.indexOf("//")) + 3,
                    source.lastIndexOf("ret") + 3,
                )

            offsets.forEach { offset ->
                val result = fixture.complete("main.kt", offset)
                assertTrue(result.items.none { it.kind == CompletionKind.Keyword }, result.items.toString())
            }
        }

        val interpolation = "fun main() { val text = \"\${ret}\" }"
        K2QueryFixture.source("main.kt" to interpolation).use { fixture ->
            val result = fixture.complete("main.kt", interpolation.indexOf("ret") + 3)

            assertEquals(CompletionKind.Keyword, result.items.single { it.insertText == "return" }.kind)
        }
    }

    @Test
    fun `completion resolves local variables after dollar in string template`() {
        val source = "fun main() { val localValue = 1; val text = \"\$loc\" }"
        val prefixStart = source.indexOf("loc\"")
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", prefixStart + 3)

            assertEquals(EditorRange(prefixStart, prefixStart + 3), result.replacement)
            assertTrue(result.items.any { it.insertText == "localValue" }, result.items.toString())
        }
    }

    @Test
    fun `completion immediately after dollar offers local variables`() {
        val source = "fun main() { val localValue = 1; val text = \"\$\" }"
        val caret = source.indexOf("\$\"") + 1
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", caret)

            assertEquals(EditorRange(caret, caret), result.replacement)
            assertTrue(result.items.any { it.insertText == "localValue" }, result.items.toString())
        }
    }

    @Test
    fun `unqualified completion sees lexical and package declarations`() {
        val declarations = "package sample\nfun localPackageFunction() = Unit"
        val source = "package sample\nfun main(parameter: String) { val localValue = 1; loc }"
        K2QueryFixture.source("declarations.kt" to declarations, "main.kt" to source).use { fixture ->
            val prefixStart = source.lastIndexOf("loc")
            val result = fixture.complete("main.kt", prefixStart + 3)

            assertEquals(EditorRange(prefixStart, prefixStart + 3), result.replacement)
            assertEquals("localValue", result.items.first().label)
            assertEquals("localValue", result.items.first().insertText)
            assertEquals(CompletionKind.LocalVariable, result.items.first().kind)
            assertTrue(result.items.any { it.insertText == "localPackageFunction" })
            assertTrue(result.items.none { it.insertText == "parameter" })
        }
    }

    @Test
    fun `unqualified completion proposes an import for a declaration in another project package`() {
        val library = "package library\nobject RemoteApi"
        val source = "package application\n\nfun main() { Rem }"
        K2QueryFixture.source("library.kt" to library, "main.kt" to source).use { fixture ->
            val item = fixture.complete("main.kt", source.indexOf("Rem") + 3).items.single { it.insertText == "RemoteApi" }

            assertEquals("library.RemoteApi", item.symbol?.fqName)
            assertEquals("library.RemoteApi", item.symbol?.importFqName)
            assertEquals("\n\nimport library.RemoteApi", item.additionalEdits.single().text)
        }
    }

    @Test
    fun `unqualified completion finds inactive platform modules and honors default imports`() {
        val source = "fun main() { Red }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val redstone = fixture.complete("main.kt", source.indexOf("Red") + 3).items.single { it.insertText == "Redstone" }

            assertEquals("compukter.redstone.Redstone", redstone.symbol?.fqName)
            assertEquals("compukter.redstone.Redstone", redstone.symbol?.importFqName)
            assertTrue(redstone.additionalEdits.isNotEmpty())
        }

        val defaultSource = "fun main() { printl }"
        K2QueryFixture.source("main.kt" to defaultSource).use { fixture ->
            val println = fixture.complete("main.kt", defaultSource.indexOf("printl") + 6).items.first { it.insertText == "println" }

            assertEquals("kotlin.io.println", println.symbol?.fqName)
            assertEquals(null, println.symbol?.importFqName)
            assertTrue(println.additionalEdits.isEmpty())
        }
    }

    @Test
    fun `default package import remains separated from a declaration after leading blank lines`() {
        val source = "\n\nfun main() { Red }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", source.indexOf("Red") + 3)
            val redstone = result.items.single { it.insertText == "Redstone" }
            val importEdit = redstone.additionalEdits.single()

            assertEquals(EditorRange(0, 0), importEdit.range)
            assertEquals("import compukter.redstone.Redstone\n\n", importEdit.text)

            val editor = EditorDocument(source)
            assertIs<EditorEditResult.Applied>(
                editor.replaceRanges(
                    result.replacement,
                    redstone.insertText,
                    listOf(EditorTextEdit(importEdit.range, importEdit.text)),
                ),
            )
            assertEquals(
                "import compukter.redstone.Redstone\n\n\n\nfun main() { Redstone }",
                editor.materialize(),
            )
        }
    }

    @Test
    fun `completion inserts a qualified name when an imported short name conflicts`() {
        val source = "import other.RemoteApi\n\nfun main() { Rem }"
        val library = "package library\nobject RemoteApi"
        val other = "package other\nobject RemoteApi"
        K2QueryFixture.source("library.kt" to library, "other.kt" to other, "main.kt" to source).use { fixture ->
            val item =
                fixture.complete("main.kt", source.lastIndexOf("Rem") + 3).items.single {
                    it.symbol?.fqName == "library.RemoteApi"
                }

            assertEquals("library.RemoteApi", item.insertText)
            assertEquals(null, item.symbol?.importFqName)
            assertTrue(item.additionalEdits.isEmpty())
        }
    }

    @Test
    fun `qualified completion uses inferred receiver members and applicable extensions`() {
        val source =
            """
            fun String.stringExtension() = Unit
            fun Int.intExtension() = Unit
            fun unrelated() = Unit

            fun main() {
                val inferred = "value"
                inferred.
            }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", source.indexOf("inferred.") + "inferred.".length, CompletionTrigger.Manual)
            val labels = result.items.map { it.insertText }

            assertTrue("length" in labels, labels.toString())
            assertTrue("stringExtension" in labels, labels.toString())
            assertTrue("intExtension" !in labels, labels.toString())
            assertTrue("unrelated" !in labels, labels.toString())
            assertEquals(
                EditorRange(source.indexOf("inferred.") + "inferred.".length, source.indexOf("inferred.") + "inferred.".length),
                result.replacement,
            )
        }
    }

    @Test
    fun `qualified completion exposes Float conversion members on a parameter`() {
        val source = "fun convert(speed: Float): Int = speed.to"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val items = fixture.complete("main.kt", source.length).items

            assertTrue(items.any { it.label == "toInt()" && it.insertText == "toInt" }, items.toString())
        }
    }

    @Test
    fun `qualified completion exposes Double conversion members on a parameter`() {
        val source = "fun convert(speed: Double): Int = speed.to"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val items = fixture.complete("main.kt", source.length).items

            listOf("toInt", "toLong", "toFloat").forEach { name ->
                assertTrue(items.any { it.label == "$name()" && it.insertText == name }, items.toString())
            }
        }
    }

    @Test
    fun `unqualified completion includes imported and implicit receiver scopes`() {
        val library = "package library\nfun importedFunction() = Unit"
        val source =
            """
            package sample
            import library.importedFunction

            class Host {
                fun implicitMember() = Unit
                fun run() { imp }
            }
            """.trimIndent()
        K2QueryFixture.source("library.kt" to library, "main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", source.lastIndexOf("imp") + 3)
            val labels = result.items.map { it.insertText }

            assertTrue("implicitMember" in labels, labels.toString())
            assertTrue("importedFunction" in labels, labels.toString())
        }
    }

    @Test
    fun `unqualified extensions require an applicable implicit receiver`() {
        val source =
            """
            fun String.extensionForString() = Unit
            fun Int.extensionForInt() = Unit
            fun String.run() { ext }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", source.lastIndexOf("ext") + 3)
            val labels = result.items.map { it.insertText }

            assertTrue("extensionForString" in labels, labels.toString())
            assertTrue("extensionForInt" !in labels, labels.toString())
        }
    }

    @Test
    fun `completion excludes inaccessible declarations`() {
        val hidden = "package sample\nprivate fun hiddenFunction() = Unit"
        val source = "package sample\nfun main() { hid }"
        K2QueryFixture.source("hidden.kt" to hidden, "main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", source.indexOf("hid") + 3)

            assertTrue(result.items.none { it.insertText == "hiddenFunction" }, result.items.toString())
        }
    }

    @Test
    fun `completion preserves overloads and orders them deterministically`() {
        val source =
            """
            fun choose(value: String) = value
            fun choose(value: Int) = value
            fun main() { cho }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val first = fixture.complete("main.kt", source.lastIndexOf("cho") + 3).items
            val second = fixture.complete("main.kt", source.lastIndexOf("cho") + 3).items

            assertEquals(2, first.count { it.insertText == "choose" })
            assertEquals(setOf("choose(value: Int)", "choose(value: String)"), first.take(2).map { it.label }.toSet())
            assertTrue(first.any { it.insertText == "charArrayOf" })
            assertEquals(first, second)
            val details = first.filter { it.insertText == "choose" }.map { requireNotNull(it.detail) }
            assertEquals(details.sorted(), details)
        }
    }

    @Test
    fun `completion gives platform println overloads distinct argument labels`() {
        val source = "fun main() { printl }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val items = fixture.complete("main.kt", source.indexOf("printl") + "printl".length).items
            val printlnItems = items.filter { it.insertText == "println" }

            assertTrue(printlnItems.size > 1, printlnItems.toString())
            assertEquals(printlnItems.size, printlnItems.map { it.label }.distinct().size, printlnItems.joinToString("\n"))
            assertTrue(printlnItems.any { it.label == "println()" }, printlnItems.toString())
            assertTrue(printlnItems.any { it.label == "println(value: Int)" }, printlnItems.toString())
            assertTrue(printlnItems.all { it.insertText == "println" }, printlnItems.toString())
            assertEquals(CompletionCallShape(false, false, false), printlnItems.single { it.label == "println()" }.callShape)
            assertEquals(CompletionCallShape(true, true, false), printlnItems.single { it.label == "println(value: Int)" }.callShape)
        }
    }

    @Test
    fun `completion exposes the side-oriented redstone API`() {
        val redstoneSource = "import compukter.redstone.Redstone\nfun main() { Redstone. }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to redstoneSource).use { fixture ->
            val items = fixture.complete("main.kt", redstoneSource.indexOf("Redstone.") + "Redstone.".length).items
            val names = items.map { it.insertText }.toSet()

            assertTrue(names.containsAll(setOf("Power", "front", "back", "left", "right", "top", "bottom")), items.toString())
            assertTrue("outputs" !in names, items.toString())
        }

        val sideSource = "import compukter.redstone.Redstone\nfun main() { Redstone.left. }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to sideSource).use { fixture ->
            val items = fixture.complete("main.kt", sideSource.indexOf("Redstone.left.") + "Redstone.left.".length).items

            assertEquals(2, items.count { it.insertText == "await" }, items.toString())
            assertTrue(items.any { it.insertText == "get" }, items.toString())
            assertTrue(items.any { it.insertText == "set" }, items.toString())
            assertTrue(items.any { it.insertText == "awaitAtLeast" }, items.toString())
        }

        val powerSource = "import compukter.redstone.Redstone\nfun main() { Redstone.Power. }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to powerSource).use { fixture ->
            val items = fixture.complete("main.kt", powerSource.indexOf("Redstone.Power.") + "Redstone.Power.".length).items

            assertTrue(items.map { it.insertText }.containsAll(setOf("WEAK", "DIRECT")), items.toString())
        }
    }

    @Test
    fun `completion exposes typed addon sides and devices`() {
        val kineticsSource = "import fixture.kinetics.Kinetics\nfun main() { Kinetics. }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to kineticsSource).use { fixture ->
            val items = fixture.complete("main.kt", kineticsSource.indexOf("Kinetics.") + "Kinetics.".length).items

            assertTrue(
                items.map { it.insertText }.containsAll(setOf("front", "back", "left", "right", "top", "bottom")),
                items.toString(),
            )
        }

        val sideSource = "import fixture.kinetics.Kinetics\nfun main() { Kinetics.left. }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to sideSource).use { fixture ->
            val items = fixture.complete("main.kt", sideSource.indexOf("Kinetics.left.") + "Kinetics.left.".length).items

            assertTrue(
                items.map { it.insertText }.containsAll(setOf("speedometer", "stressometer", "rotationController")),
                items.toString(),
            )
        }
    }

    @Test
    fun `completion proposes an available addon API before its module is selected`() {
        val source = "fun main() { Kin }"
        K2QueryFixture.sourceWithInactiveGuestApi("main.kt" to source).use { fixture ->
            val kinetics = fixture.complete("main.kt", source.indexOf("Kin") + 3).items.single { it.insertText == "Kinetics" }

            assertEquals("fixture.kinetics.Kinetics", kinetics.symbol?.fqName)
            assertEquals("fixture.kinetics.Kinetics", kinetics.symbol?.importFqName)
            assertEquals(
                "fixture:api",
                assertIs<DeclarationOrigin.Platform>(kinetics.origin).identity.name,
            )
            val addon =
                AddonGuestApiBundleCodec.decode(
                    Files.readAllBytes(Path.of(requireNotNull(System.getProperty("compukters.test.addonGuestApiFixture")))),
                )
            assertContentEquals(
                addon.identity.contentHash.toByteArray(),
                assertIs<DeclarationOrigin.Platform>(kinetics.origin).identity.hash.toByteArray(),
            )
        }
    }

    @Test
    fun `completion tolerates synthetic function interfaces from platform libraries`() {
        val source = "fun main() { Fun }"
        K2QueryFixture.sourceWithGuestApi(false, "main.kt" to source).use { fixture ->
            val result = fixture.complete("main.kt", source.indexOf("Fun") + "Fun".length)
            val function = result.items.single { it.insertText == "Function0" }

            assertEquals(CompletionKind.Interface, function.kind)
            assertEquals(
                "kotlin:builtins",
                assertIs<DeclarationOrigin.Platform>(function.origin).identity.name,
            )
        }
    }

    @Test
    fun `completion reports public classifier kinds`() {
        val source =
            """
            interface VisibleInterface
            object VisibleObject
            class VisibleClass
            fun <VisibleType> use() { Vis }
            """.trimIndent()
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val kinds = fixture.complete("main.kt", source.lastIndexOf("Vis") + 3).items.associate { it.label to it.kind }

            assertEquals(CompletionKind.Interface, kinds["VisibleInterface"])
            assertEquals(CompletionKind.Object, kinds["VisibleObject"])
            assertEquals(CompletionKind.Class, kinds["VisibleClass"])
            assertEquals(CompletionKind.TypeParameter, kinds["VisibleType"])
        }
    }

    @Test
    fun `completion handles Unicode prefixes in red code`() {
        val source = "fun приветствие() = Unit\nfun main() { unknown(\n при }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val prefixStart = source.lastIndexOf("при")
            val result = fixture.complete("main.kt", prefixStart + "при".length)

            assertEquals(EditorRange(prefixStart, prefixStart + "при".length), result.replacement)
            assertTrue(result.items.any { it.insertText == "приветствие" }, result.items.toString())
        }
    }

    @Test
    fun `completion replacement keeps supplementary Unicode identifier code points`() {
        val name = "𐐀name"
        val prefix = "𐐀n"
        val source = "fun $name() = Unit\nfun main() { $prefix }"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val prefixStart = source.lastIndexOf(prefix)
            val result = fixture.complete("main.kt", prefixStart + prefix.length)

            assertEquals(EditorRange(prefixStart, prefixStart + prefix.length), result.replacement)
            assertTrue(result.items.any { it.insertText == name }, result.items.toString())
        }
    }

    @Test
    fun `manual empty prefix completion obeys the 256 item cap`() {
        val declarations = (0 until 300).joinToString("\n") { "fun candidate%03d() = Unit".format(it) }
        val source = "$declarations\nfun main() { \n}"
        K2QueryFixture.source("main.kt" to source).use { fixture ->
            val offset = source.lastIndexOf('\n')
            val result = fixture.complete("main.kt", offset, CompletionTrigger.Manual)

            assertEquals(EditorRange(offset, offset), result.replacement)
            assertEquals(256, result.items.size)
            assertEquals((0 until 256).map { "candidate%03d".format(it) }, result.items.map { it.insertText })
        }
    }

    @Test
    fun `manual completion works in an empty immutable file`() {
        K2QueryFixture.source("main.kt" to "").use { fixture ->
            val result = fixture.complete("main.kt", 0, CompletionTrigger.Manual)

            assertEquals(EditorRange(0, 0), result.replacement)
            assertTrue(result.items.any { it.label == "Any" }, result.items.toString())
        }
    }
}

private fun K2QueryFixture.complete(
    path: String,
    offset: Int,
    trigger: CompletionTrigger = CompletionTrigger.Automatic,
): AnalysisResult.Completion =
    execute(
        AnalysisQuery.Completion(identity, VirtualSourcePath.kotlin(path), offset, trigger),
    ) as AnalysisResult.Completion
