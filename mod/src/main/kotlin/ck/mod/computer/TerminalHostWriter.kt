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

package ck.mod.computer

import ck.mod.gui.NetworkedTerminal

/**
 * Applies terminal host writes while keeping the terminal cursor in sync with what the VM expects.
 */
object TerminalHostWriter {
    fun write(
        terminal: NetworkedTerminal,
        text: String,
    ) {
        terminal.write(text)
        terminal.setCursorPos(terminal.cursorX + text.length, terminal.cursorY)
    }

    fun printLine(
        terminal: NetworkedTerminal,
        text: String,
    ) {
        write(terminal, text.take(terminal.width))
        if (terminal.cursorY >= terminal.height - 1) {
            terminal.scroll(1)
            terminal.setCursorPos(0, terminal.height - 1)
        } else {
            terminal.setCursorPos(0, terminal.cursorY + 1)
        }
    }
}
