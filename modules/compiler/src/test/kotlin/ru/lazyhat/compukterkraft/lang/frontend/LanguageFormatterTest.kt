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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageFormatterTest {
    private val parser = DefaultParserFacade()

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
}
