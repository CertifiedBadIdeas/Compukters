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

package ru.lazyhat.compukters.ide.documentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KDocDocumentTest {
    @Test
    fun `tags keep subjects separate from multiline descriptions`() {
        val doc =
            KDocDocument.parse(
                """
                Sends a message.

                Detailed description.
                @param[data] message bytes
                continued on another line
                @param T type of result
                @return acknowledgement
                @throws java.io.IOException when unavailable
                @exception Failure other failure
                @receiver remote peer
                @property size current size
                @constructor opens the peer
                @sample demo.exchange
                @see [Peer] related peer
                @since 0.6
                @author Example
                @suppress
                """.trimIndent(),
            )
        assertEquals("Sends a message.\n\nDetailed description.", doc.description)
        assertEquals(listOf("data", "T"), doc.tags.filter { it.kind == KDocTagKind.Param }.map { it.subject })
        assertEquals("message bytes\ncontinued on another line", doc.tags.first().description)
        assertEquals(listOf("java.io.IOException", "Failure"), doc.tags.filter { it.kind == KDocTagKind.Throws }.map { it.subject })
        assertTrue(KDocTagKind.entries.filter { it != KDocTagKind.Unknown }.all { kind -> doc.tags.any { it.kind == kind } })
    }

    @Test
    fun `literal code and escaped tags do not become sections`() {
        val doc = KDocDocument.parse("Description\n```kotlin\n@param literal\n```\n    @return code\n\\@see escaped\n@return actual")
        assertEquals(listOf(KDocTagKind.Return), doc.tags.map { it.kind })
        assertTrue("@param literal" in doc.description)
        assertEquals("actual", doc.tags.single().description)
    }

    @Test
    fun `unknown and incomplete tags retain text without throwing`() {
        val doc = KDocDocument.parse("@param\n@return\n@custom text 😀")
        assertEquals(null, doc.tags.first().subject)
        assertEquals("text 😀", doc.tags.last().description)
        assertEquals(KDocTagKind.Unknown, doc.tags.last().kind)
    }
}
