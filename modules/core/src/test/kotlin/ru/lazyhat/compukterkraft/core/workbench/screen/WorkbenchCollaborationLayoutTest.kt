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
package ru.lazyhat.compukterkraft.core.workbench.screen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.lazyhat.compukterkraft.core.workbench.EditorPresence
import ru.lazyhat.compukterkraft.core.workbench.crdt.SiteId

class WorkbenchCollaborationLayoutTest {
    @Test
    fun `remoteCaretPixelX uses prefix-width measurer (variable-width font)`() {
        // 'i' is 2 px, 'W' is 6 px (typical MC asymmetry).
        val widths = mapOf('i' to 2, 'W' to 6, 'a' to 5)
        val measure: (String) -> Int = { s -> s.sumOf { ch -> widths[ch] ?: 4 } }

        val line = "iWiWiW"
        val column = 6
        val x = remoteCaretPixelX(line, column, leftPad = 10, gutter = 8, measure = measure)

        // Variable-width: 3*(2+6) = 24. Fixed-width × 6 px would have given 36.
        assertEquals(10 + 8 + 24, x)
    }

    @Test
    fun `remoteCaretPixelX clamps column to line length`() {
        val measure: (String) -> Int = { it.length * 5 }
        val x = remoteCaretPixelX(textLine = "abc", column = 999, leftPad = 0, gutter = 0, measure = measure)
        assertEquals(15, x)
    }

    @Test
    fun `presencesForRecipient drops the recipient's own entry`() {
        val mine = SiteId("p:aaaa")
        val theirs = SiteId("p:bbbb")
        val list =
            listOf(
                EditorPresence(mine, "Me", "main.ck"),
                EditorPresence(theirs, "Them", "main.ck"),
            )

        val filtered = presencesForRecipient(list, mine)

        assertEquals(listOf(EditorPresence(theirs, "Them", "main.ck")), filtered)
    }

    @Test
    fun `presencesForRecipient is a no-op when own site absent`() {
        val mine = SiteId("p:aaaa")
        val list = listOf(EditorPresence(SiteId("p:bbbb"), "Them", "main.ck"))
        assertEquals(list, presencesForRecipient(list, mine))
    }
}
