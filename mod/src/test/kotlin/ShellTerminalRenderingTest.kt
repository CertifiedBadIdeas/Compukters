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

import ck.mod.computer.TerminalHostWriter
import ck.mod.gui.NetworkedTerminal
import kotlin.test.Test
import kotlin.test.assertEquals

class ShellTerminalRenderingTest {
    @Test
    fun appendsTypedCharactersAfterPrompt() {
        val terminal = NetworkedTerminal(12, 2, true)

        TerminalHostWriter.write(terminal, "/ > ")
        TerminalHostWriter.write(terminal, "abc")

        assertEquals("/ > abc     ", terminal.getLine(0).toString())
        assertEquals(7, terminal.cursorX)
        assertEquals(0, terminal.cursorY)
    }

    @Test
    fun preservesPromptWhenBackspaceErasesLastTypedCharacter() {
        val terminal = NetworkedTerminal(12, 2, true)

        TerminalHostWriter.write(terminal, "/ > ")
        TerminalHostWriter.write(terminal, "ab")

        terminal.setCursorPos(5, 0)
        TerminalHostWriter.write(terminal, " ")
        terminal.setCursorPos(5, 0)

        assertEquals("/ > a       ", terminal.getLine(0).toString())
        assertEquals(5, terminal.cursorX)
        assertEquals(0, terminal.cursorY)
    }

    @Test
    fun movesToNextLineAfterSubmittingInput() {
        val terminal = NetworkedTerminal(12, 2, true)

        TerminalHostWriter.write(terminal, "/ > ")
        TerminalHostWriter.write(terminal, "42")
        TerminalHostWriter.printLine(terminal, "")

        assertEquals("/ > 42      ", terminal.getLine(0).toString())
        assertEquals("            ", terminal.getLine(1).toString())
        assertEquals(0, terminal.cursorX)
        assertEquals(1, terminal.cursorY)
    }
}
