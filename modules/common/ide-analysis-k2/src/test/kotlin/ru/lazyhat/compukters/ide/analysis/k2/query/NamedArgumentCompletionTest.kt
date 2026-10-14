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

import ru.lazyhat.compukters.ide.analysis.CompletionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamedArgumentCompletionTest {
    @Test
    fun `named arguments complete missing names without replacing expression completion`() {
        val declarations = "fun send(position: Int, color: Int = 0) {}\nfun main() { val positionValue = 1; "
        for (call in listOf("send(po|)", "send( |)", "send(color = 1, po|)")) {
            val marked = declarations + call + " }"
            val offset = marked.indexOf('|')
            K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", offset).items
                val named = items.single { it.insertText == "position = " }
                assertEquals(CompletionKind.Parameter, named.kind)
                assertTrue(named.detail!!.contains("Int"))
                if (call.contains("po|")) assertTrue(items.any { it.insertText == "positionValue" })
                if (call.contains("color =")) assertTrue(items.none { it.insertText == "color = " })
            }
        }
    }

    @Test
    fun `named arguments exclude positional named and trailing lambda bindings`() {
        val declarations = "fun send(position: Int, color: Int = 0, block: () -> Unit = {}) {}\nfun main() { "
        val marked = declarations + "send(1, |) {} }"
        K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", marked.indexOf('|')).items
            assertTrue(items.any { it.insertText == "color = " })
            assertTrue(items.none { it.insertText in setOf("position = ", "block = ") })
        }
    }

    @Test
    fun `named arguments complete constructor and member overload parameters`() {
        val declarations =
            "class Panel(val position: Int) { fun draw(position: Int) {}\n" +
                "fun draw(color: String) {} }\nfun main() { val panel = Panel(1); "
        for ((call, expected) in listOf("Panel(po|)" to setOf("position = "), "panel.draw(|)" to setOf("position = ", "color = "))) {
            val marked = declarations + call + " }"
            K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items
                assertEquals(expected, items.map { it.insertText }.filter { it.endsWith(" = ") }.toSet())
            }
        }
    }

    @Test
    fun `named argument labels preserve existing equals and values do not offer labels`() {
        val declarations = "fun send(position: Int, color: Int = 0) {}\nfun main() { val positionValue = 1; "
        for (call in listOf("send(po| = 1)", "send(position = po|)", "send(1 + po|)", "send(positionValue.po|)")) {
            val marked = declarations + call + " }"
            K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items
                if (call == "send(po| = 1)") {
                    assertTrue(items.any { it.insertText == "position" && it.kind == CompletionKind.Parameter })
                }
                assertTrue(items.none { it.insertText.endsWith(" = ") }, call)
            }
        }
    }

    @Test
    fun `named arguments retain inferred extension types and escape Kotlin names`() {
        val declarations = "fun <T> T.send(position: T, `when`: Int = 0) {}\nfun main() { "
        for ((call, insert, type) in listOf(Triple("1.send(po|)", "position = ", "Int"), Triple("1.send(wh|)", "`when` = ", "Int"))) {
            val marked = declarations + call + " }"
            K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
                val item = fixture.complete("main.kt", marked.indexOf('|')).items.single { it.insertText == insert }
                assertEquals(type, item.detail)
                assertTrue(item.matchedNameRanges.isNotEmpty())
                assertEquals(if (insert.startsWith('`')) 1 else 0, item.matchedNameRanges.first().startUtf16)
            }
        }
    }

    @Test
    fun `named arguments exclude vararg and names supplied after caret`() {
        val declarations = "fun send(vararg values: Int, color: Int = 0) {}\nfun main() { "
        for (call in listOf("send(1, |)", "send(co|, color = 2)")) {
            val marked = declarations + call + " }"
            K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items
                if (call.startsWith("send(1")) {
                    assertTrue(items.any { it.insertText == "color = " })
                    assertTrue(items.none { it.insertText == "values = " })
                } else {
                    assertTrue(items.none { it.insertText == "color = " })
                }
            }
        }
    }

    @Test
    fun `named arguments use platform overload names without attached SDK sources`() {
        for (attachedSources in listOf(false, true)) {
            val marked = "import compukter.display.GraphicalDisplay\nfun main(screen: GraphicalDisplay) { screen.pixel(po|) }"
            K2QueryFixture.sourceWithGuestApi(attachedSources, "main.kt" to marked.replace("|", "")).use { fixture ->
                val items = fixture.complete("main.kt", marked.indexOf('|')).items
                assertTrue(
                    items.any {
                        it.insertText == "position = " &&
                            (it.detail!!.contains("Vector2i") || it.detail!!.contains("Position2i"))
                    },
                    items.toString(),
                )
            }
        }
    }

    @Test
    fun `function type invocation does not offer unsupported named arguments`() {
        val marked = "fun main() { val action: (Int) -> Unit = {}; action(|) }"
        K2QueryFixture.source("main.kt" to marked.replace("|", "")).use { fixture ->
            val items = fixture.complete("main.kt", marked.indexOf('|')).items
            assertTrue(items.none { it.insertText.endsWith(" = ") }, items.toString())
        }
    }
}
