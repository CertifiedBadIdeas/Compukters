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

import ru.lazyhat.compukterkraft.core.computer.vm.TerminalLineReader
import ru.lazyhat.compukterkraft.core.computer.vm.VmContext
import ru.lazyhat.compukterkraft.lang.runtime.ComputerStdioApi
import ru.lazyhat.compukterkraft.lang.runtime.ComputerTerminalApi
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer

/**
 * Terminal API implementation routed through the [ComputerStdioApi] byte stream.
 *
 * Since Epic 1, [write], [printLine], [clear] and [setCursor] no longer mutate
 * [screenBuffer] directly — they emit VT-100 escape sequences via [stdio]. The
 * server-side [VmStdioApi] parses them back into the same ScreenBuffer, so
 * observable behaviour is unchanged.
 *
 * [screenBuffer] is retained for [readLine], which still needs direct cursor
 * blink control and positional reads; full removal is tracked for Epic 2.
 */
class VmTerminalApi(
    private val stdio: ComputerStdioApi,
    override val screenBuffer: ScreenBuffer,
    private val ctx: VmContext,
) : ComputerTerminalApi {
    override fun write(text: String) {
        stdio.writeString(text)
    }

    override fun printLine(text: String) {
        stdio.writeString(text)
        stdio.writeString("\n")
    }

    override suspend fun readLine(prompt: String): String {
        // DECTCEM — emit the cursor-visible VT sequence so every attached client
        // flips its own `cursorBlink` flag through the VtParser. The server-side
        // ScreenBuffer is still driven via the broadcaster's internal consumer,
        // so the legacy snapshot path sees the same transition.
        stdio.writeString("\u001B[?25h")
        try {
            return TerminalLineReader(
                receiveEvent = { ctx.receiveEvent() },
                deferEvent = ctx::deferEvent,
                write = ::write,
                printLine = ::printLine,
                setCursor = ::setCursor,
                currentCursor = { screenBuffer.cursorX to screenBuffer.cursorY },
                // no-op: setCursor already updates screenBuffer via stdio
                updateCursor = { _, _ -> },
            ).readLine(prompt)
        } finally {
            stdio.writeString("\u001B[?25l")
        }
    }

    override fun clear() {
        // CSI 2J = erase entire display; CSI H = home cursor.
        stdio.writeString("\u001B[2J\u001B[H")
    }

    override fun setCursor(
        x: Int,
        y: Int,
    ) {
        // VT-100 is 1-based (row;col); project cursor is 0-based (col=x, row=y).
        stdio.writeString("\u001B[${y + 1};${x + 1}H")
    }
}
