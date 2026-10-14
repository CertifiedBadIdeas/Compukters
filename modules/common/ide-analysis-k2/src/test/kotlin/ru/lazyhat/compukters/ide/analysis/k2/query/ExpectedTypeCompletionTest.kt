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
import ru.lazyhat.compukters.ide.analysis.CompletionTrigger
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpectedTypeCompletionTest {
    @Test
    fun `positional and named arguments rank compatible values without hiding other types`() {
        val declarations = "fun send(position: Int) {}\nfun main() { val choiceBad = \"bad\"; val choiceGood = 1; "
        for (call in listOf("send(choice|)", "send(position = choice|)")) {
            val marked = declarations + call + " }"
            K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
                assertTrue(items.indexOf("choiceGood") < items.indexOf("choiceBad"), items.toString())
                assertTrue("choiceBad" in items)
            }
        }
    }

    @Test
    fun `nullable and subtype compatibility preserve Kotlin assignability`() {
        val declarations =
            "open class Base; class Derived : Base()\nfun send(value: Base) {}\n" +
                "fun main() { val choiceNullable: Base? = null; val choiceSubtype = Derived(); "
        val marked = declarations + "send(choice|) }"
        K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
            assertTrue(items.indexOf("choiceSubtype") < items.indexOf("choiceNullable"), items.toString())
            assertTrue("choiceNullable" in items)
        }
    }

    @Test
    fun `generic inference and overloads rank values from applicable parameter types`() {
        for (declarations in listOf(
            "fun <T> send(first: T, second: T) {}",
            "fun send(first: Int, second: Int) {}\nfun send(first: String, second: String) {}",
        )) {
            val marked = declarations + "\nfun main() { val choiceBad = \"bad\"; val choiceGood = 2; send(1, choice|) }"
            K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
                assertTrue(items.indexOf("choiceGood") < items.indexOf("choiceBad"), items.toString())
            }
        }
    }

    @Test
    fun `Boolean and nullable argument values offer literals for an empty prefix`() {
        for ((type, literals) in listOf("Boolean" to setOf("true", "false"), "Boolean?" to setOf("true", "false", "null"))) {
            val marked = "fun send(value: $type) {}\nfun main() { send(value = |) }"
            K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
                assertTrue(items.take(literals.size).toSet() == literals, items.toString())
            }
        }
    }

    @Test
    fun `expected enum values use valid qualified insertion and retain prefix matching`() {
        val marked = "enum class Mode { FIRST, SECOND }\nfun send(mode: Mode) {}\nfun main() { send(mode = FI|) }"
        K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", marked.indexOf('|')).items
            assertEquals("Mode.FIRST", items.first().insertText)
            assertTrue(items.first().matchedNameRanges.isNotEmpty())
        }
    }

    @Test
    fun `call results and qualified properties use their substituted result types`() {
        val marked =
            "fun send(value: Int) {}\nclass Values { val choiceBad = \"bad\"; val choiceGood = 1 }\n" +
                "fun main() { val values = Values(); send(value = values.choice|) }"
        K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
            assertTrue(items.indexOf("choiceGood") < items.indexOf("choiceBad"), items.toString())
        }
        val functions = "fun send(value: Int) {}\nfun choiceBad() = \"bad\"\nfun choiceGood() = 1\nfun main() { send(value = choice|) }"
        K2QueryFixture.source("main.kt" to functions.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", functions.indexOf('|')).items.map { it.insertText }
            assertTrue(items.indexOf("choiceGood") < items.indexOf("choiceBad"), items.toString())
        }
    }

    @Test
    fun `vararg spread and safe call results respect argument value types`() {
        val declarations = "fun send(vararg values: Int) {}\nfun main() { val choiceBad = \"bad\"; val choiceGood = 1; "
        val marked = declarations + "send(1, choice|) }"
        K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
            assertTrue(items.indexOf("choiceGood") < items.indexOf("choiceBad"), items.toString())
        }
        val spread = "fun send(vararg values: Int) {}\nfun main() { val choiceBad = 1; val choiceGood = intArrayOf(1); send(*choice|) }"
        K2QueryFixture.source("main.kt" to spread.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", spread.indexOf('|')).items.map { it.insertText }
            assertTrue(items.indexOf("choiceGood") < items.indexOf("choiceBad"), items.toString())
        }
        val safe =
            "fun send(value: Int) {}\nclass Values { val choiceBad = \"bad\"; val choiceGood = 1 }\n" +
                "fun main(values: Values?) { send(values?.choice|) }"
        K2QueryFixture.source("main.kt" to safe.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", safe.indexOf('|')).items.map { it.insertText }
            // Both nullable results are incompatible with Int; keep ordinary name ordering.
            assertTrue(items.indexOf("choiceBad") < items.indexOf("choiceGood"), items.toString())
        }
    }

    @Test
    fun `autoimported property types take priority while keeping their import edits`() {
        val marked = "fun send(value: Int) {}\nfun main() { send(value = choice|) }"
        K2QueryFixture
            .source(
                "main.kt" to marked.replace("|", ""),
                "values.kt" to "package sample\nval choiceBad = \"bad\"\nval choiceGood = 1",
            ).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items
                assertEquals("choiceGood", items.first().insertText)
                assertTrue(items.first().additionalEdits.any { it.text.contains("import sample.choiceGood") })
                assertTrue(items.any { it.insertText == "choiceBad" })
            }
    }

    @Test
    fun `SDK aliases and generic type arguments use semantic compatibility`() {
        for (attachedSources in listOf(false, true)) {
            val marked =
                "import compukter.display.GraphicalDisplay\nimport kotlin.math.Vector2i\n" +
                    "fun main(screen: GraphicalDisplay) { val choiceBad = 1; val choiceGood = Vector2i(1, 2); screen.pixel(position = choice|, color = 0) }"
            K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
                assertTrue(items.indexOf("choiceGood") < items.indexOf("choiceBad"), items.toString())
            }
        }
        val marked =
            "fun send(value: List<Int>) {}\nfun main() { val choiceBad: List<String> = listOf(\"bad\"); " +
                "val choiceGood: List<Int> = listOf(1); send(choice|) }"
        K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
            assertTrue(items.indexOf("choiceGood") < items.indexOf("choiceBad"), items.toString())
        }
    }

    @Test
    fun `unrelated contexts and unresolved calls retain ordinary completion ordering`() {
        for (body in listOf("val output: Int = choice|", "missing(choice|)")) {
            val marked = "fun main() { val choiceBad = \"bad\"; val choiceGood = 1; $body }"
            K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
                assertTrue(items.indexOf("choiceBad") < items.indexOf("choiceGood"), items.toString())
            }
        }
    }

    @Test
    fun `exact expected types rank before compatible subtypes and item caps preserve matches`() {
        val marked =
            "open class Base; class Derived : Base()\nfun send(value: Base) {}\n" +
                "fun main() { val choiceBad = \"bad\"; val choiceDerived = Derived(); val choiceExact: Base = Derived(); send(value = choice|) }"
        K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
            val query =
                AnalysisQuery.Completion(
                    fixture.identity,
                    ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
                        .kotlin("main.kt"),
                    marked.indexOf('|'),
                    CompletionTrigger.Manual,
                )
            val items = fixture.complete("main.kt", marked.indexOf('|')).items.map { it.insertText }
            assertTrue(items.indexOf("choiceExact") < items.indexOf("choiceDerived"), items.toString())
            assertTrue(items.indexOf("choiceDerived") < items.indexOf("choiceBad"), items.toString())
            val capped =
                fixture.execute(
                    query,
                    ru.lazyhat.compukters.ide.analysis.protocol
                        .AnalysisLimits(completionItems = 1),
                )
                    as AnalysisResult.Completion
            assertEquals(listOf("choiceExact"), capped.items.map { it.insertText })
        }
    }

    @Test
    fun `enum suggestions insert qualified cross-package names and highlight escaped entries`() {
        val marked = "fun main() { sample.send(mode = wh|) }"
        K2QueryFixture
            .source(
                "main.kt" to marked.replace("|", ""),
                "mode.kt" to "package sample\nenum class Mode { `when` }\nfun send(mode: Mode) {}",
            ).use { fixture ->
                val item = fixture.complete("main.kt", marked.indexOf('|')).items.first()
                assertEquals("sample.Mode.`when`", item.insertText)
                assertEquals("wh", item.label.substring(item.matchedNameRanges.first().startUtf16, item.matchedNameRanges.first().endUtf16))
            }
    }

    @Test
    fun `short string interpolation keeps name-only completion without value literals`() {
        val marked = "fun send(value: String) {}\nfun main() { val track = 1; send(\"${'$'}tr|\") }"
        K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", marked.indexOf('|')).items
            assertTrue(items.any { it.insertText == "track" }, items.toString())
            assertTrue(items.none { it.insertText in setOf("true", "false", "null") }, items.toString())
        }
    }
}
