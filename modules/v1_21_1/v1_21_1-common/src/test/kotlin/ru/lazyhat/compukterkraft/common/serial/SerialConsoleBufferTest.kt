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
package ru.lazyhat.compukterkraft.common.serial

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SerialConsoleBufferTest {
    @Test
    fun `output bytes are collected into history lines`() {
        val buffer = SerialConsoleBuffer()

        buffer.appendOutput("RUX READY\nhel".encodeToByteArray())
        buffer.appendOutput("lo\n>".encodeToByteArray())

        assertEquals(listOf("RUX READY", "hello"), buffer.historyLines)
        assertEquals(">", buffer.pendingOutputLine)
    }

    @Test
    fun `reset replaces previous output history`() {
        val buffer = SerialConsoleBuffer()
        buffer.appendOutput("old\n".encodeToByteArray())

        buffer.appendOutput("new\n".encodeToByteArray(), reset = true)

        assertEquals(listOf("new"), buffer.historyLines)
        assertEquals("", buffer.pendingOutputLine)
    }

    @Test
    fun `input line submits utf8 bytes with newline`() {
        val buffer = SerialConsoleBuffer()
        buffer.type('o')
        buffer.type('k')

        assertContentEquals("ok\n".encodeToByteArray(), buffer.submitLine())
        assertEquals("", buffer.inputLine)
        assertContentEquals("\n".encodeToByteArray(), buffer.submitLine())
    }

    @Test
    fun `backspace edits only the local input line`() {
        val buffer = SerialConsoleBuffer()
        buffer.type('a')
        buffer.type('b')
        buffer.backspace()

        assertEquals("a", buffer.inputLine)
    }
}
