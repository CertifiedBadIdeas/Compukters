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

package ru.lazyhat.compukters.impl.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeKDocPresentationTest {
    @Test
    fun `documentation separates sections and preserves literal examples`() {
        val lines =
            IdeKDocPresentation.lines(
                """
                Sends **bytes** using [the peer][Peer].
                Continued description with `ByteArray`.

                - First item
                - Second item
                ```kotlin
                @param literal
                val value = "[raw]"
                ```
                @param data message bytes
                @return acknowledgement
                @throws Failure cannot send
                @exception OtherFailure connection closed
                @receiver remote endpoint
                @property size byte count
                @constructor creates endpoint
                @sample examples.send
                @see [Peer]
                @since 0.6
                @author Example
                @suppress
                """.trimIndent(),
            )
        assertTrue(lines.first().text.contains("Sends bytes using the peer."))
        assertTrue(lines.first().text.contains("Continued description"))
        assertTrue(lines.first().spans.any { it.text == "ByteArray" && it.color == IdeColors.ACCENT })
        assertTrue(lines.any { it.text == "• First item" })
        assertEquals(listOf("@param literal", "val value = \"[raw]\""), lines.filter { it.code }.map { it.text })
        assertEquals(1, lines.count { it.text == "Throws" })
        for (section in listOf(
            "Parameters",
            "Returns",
            "Receiver",
            "Properties",
            "Constructor",
            "Samples",
            "See also",
            "Since",
            "Author",
        )) {
            assertTrue(lines.any { it.text == section && it.spans.single().color == IdeColors.MUTED }, section)
        }
        val parameter = lines.single { it.text == "  data — message bytes" }
        assertEquals(IdeColors.ACCENT, parameter.spans.first().color)
        assertTrue(lines.any { it.text == "  Failure — cannot send" })
        assertTrue(lines.any { it.text == "  OtherFailure — connection closed" })
    }

    @Test
    fun `ordinary underscores escaped markup and code contents stay literal`() {
        val lines = IdeKDocPresentation.lines("some_name_here \\*literal\\* `a[b]`\n\n~~~text\n**literal**\n~~~")
        assertEquals("some_name_here *literal* a[b]", lines.first().text)
        assertEquals("**literal**", lines.last().text)
        assertTrue(lines.last().code)
    }
}
