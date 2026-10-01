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

import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionNameMatcherTest {
    @Test
    fun `matched ranges follow the same deterministic contiguous unicode match`() {
        assertEquals(listOf(EditorRange(0, 1), EditorRange(5, 6)), CompletionNameMatcher("tf").ranges("takeIf"))
        assertEquals(listOf(EditorRange(1, 4)), CompletionNameMatcher("ake").ranges("takeIf"))
        assertEquals(emptyList(), CompletionNameMatcher("tke").ranges("takeIf"))
        assertEquals(listOf(EditorRange(0, 1), EditorRange(4, 5)), CompletionNameMatcher("tt").ranges("testTest"))
        assertEquals(listOf(EditorRange(1, 4)), CompletionNameMatcher("𐐨m").ranges("a𐐀Map"))
        assertEquals(emptyList(), CompletionNameMatcher("").ranges("takeIf"))
    }

    @Test
    fun `camel matching consumes contiguous fragments of name words`() {
        for ((pattern, name) in listOf(
            "emm" to "emptyMap",
            "emli" to "emptyList",
            "eM" to "emptyMap",
            "hs" to "HTTPServer",
            "fb" to "foo_bar",
            "пк" to "пустаяКарта",
            "aabc" to "aabXAbc",
            "b2v" to "buffer2Value",
            "ti" to "takeIf",
            "tf" to "takeIf",
            "ake" to "takeIf",
            "map" to "emptyMap",
            "akf" to "takeIf",
        )) {
            val matcher = CompletionNameMatcher(pattern)
            assertTrue(matcher.quality(name) > 0, "$pattern -> $name")
            val matched = matcher.ranges(name).joinToString("") { name.substring(it.startUtf16, it.endUtf16) }
            assertEquals(pattern.lowercase(), matched.lowercase(), "$pattern -> $name")
        }
        for ((pattern, name) in listOf(
            "emty" to "emptyList",
            "emty" to "emptyMap",
            "emm" to "emptyList",
            "aaaa" to "aab",
            "tke" to "takeIf",
            "tkf" to "takeIf",
            "emty" to "empty",
            "emptly" to "emptyList",
        )) {
            assertEquals(0, CompletionNameMatcher(pattern).quality(name), "$pattern -> $name")
        }
    }

    @Test
    fun `match quality favors exact then direct then case insensitive then camel`() {
        val matcher = CompletionNameMatcher("ab")
        val qualities = listOf("ab", "abDirect", "Abacus", "AlphaBeta").map(matcher::quality)
        assertTrue(qualities.zipWithNext().all { (left, right) -> left > right })
        assertTrue(CompletionNameMatcher("").quality("anyName") > 0)
        assertEquals(0, CompletionNameMatcher("longName").quality("lo"))
        assertTrue(CompletionNameMatcher("𐐨m").quality("𐐀Map") > 0)
    }
}
