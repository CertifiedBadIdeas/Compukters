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

package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class VmStdioApiTest {
    @Test
    fun plainTextLandsInBuffer() {
        val buffer = ScreenBuffer(width = 10, height = 3, colour = true)
        val stdio = VmStdioApi(buffer)
        stdio.writeString("Hi")
        val snap = buffer.forceSnapshot()
        assertEquals('H', snap.charAt(0, 0))
        assertEquals('i', snap.charAt(1, 0))
    }

    @Test
    fun escapeSequencesAreInterpreted() {
        val buffer = ScreenBuffer(width = 10, height = 3, colour = true)
        val stdio = VmStdioApi(buffer)
        stdio.writeString("\u001B[2J\u001B[2;3HX")
        val snap = buffer.forceSnapshot()
        assertEquals('X', snap.charAt(2, 1))
    }

    @Test
    fun sgrColorsApplyToSubsequentWrites() {
        val buffer = ScreenBuffer(width = 4, height = 1, colour = true)
        val stdio = VmStdioApi(buffer)
        stdio.writeString("\u001B[31mR\u001B[0mX")
        val snap = buffer.forceSnapshot()
        assertEquals('R', snap.charAt(0, 0))
        assertEquals(1, snap.fgAt(0, 0))
        assertEquals('X', snap.charAt(1, 0))
        assertEquals(ScreenBuffer.DEFAULT_FG, snap.fgAt(1, 0))
    }
}
