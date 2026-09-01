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
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionQueryTest {
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
            assertEquals(setOf("choose(value: Int)", "choose(value: String)"), first.map { it.label }.toSet())
            assertEquals(first, second)
            val details = first.map { requireNotNull(it.detail) }
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
