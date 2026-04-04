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

package ck.mod.computer.vm

import ck.lang.runtime.ComputerTerminalApi
import ck.lang.runtime.ScreenBuffer

/**
 * Terminal API implementation that writes directly to a [ScreenBuffer].
 *
 * No HostCall roundtrip — all writes are immediate on the VM coroutine thread.
 * The server tick thread reads snapshots via [ScreenBuffer.snapshot].
 */
class VmTerminalApi(
    override val screenBuffer: ScreenBuffer,
    private val ctx: VmContext,
) : ComputerTerminalApi {
    override fun write(text: String) {
        screenBuffer.write(text)
    }

    override fun printLine(text: String) {
        screenBuffer.printLine(text)
    }

    override suspend fun readLine(prompt: String): String {
        screenBuffer.setCursorBlink(true)
        try {
            return TerminalLineReader(
                receiveEvent = { ctx.receiveEvent() },
                deferEvent = ctx::deferEvent,
                write = ::write,
                printLine = ::printLine,
                setCursor = ::setCursor,
                currentCursor = { screenBuffer.cursorX to screenBuffer.cursorY },
                updateCursor = { _, _ -> },
            ).readLine(prompt)
        } finally {
            screenBuffer.setCursorBlink(false)
        }
    }

    override fun clear() {
        screenBuffer.clear()
    }

    override fun setCursor(
        x: Int,
        y: Int,
    ) {
        screenBuffer.setCursor(x, y)
    }
}
