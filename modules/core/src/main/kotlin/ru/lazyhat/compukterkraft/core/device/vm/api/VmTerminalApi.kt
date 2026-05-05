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

package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.core.device.vm.TerminalLineReader
import ru.lazyhat.compukterkraft.core.device.vm.VmContext
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStdioApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceTerminalApi

/**
 * Terminal API routed entirely through the [DeviceStdioApi] byte stream.
 *
 * Every operation emits VT-100 escape sequences; the server-side ScreenBuffer
 * consumes the stream internally to serve the temporary Workbench snapshot path
 * while runtime computer clients render from display frames instead.
 *
 * [cursorProvider] must return the current logical cursor in (x, y) / 0-based
 * coords. Production wires this to [ComputerStdioBroadcaster.cursor].
 */
class VmTerminalApi(
    private val stdio: DeviceStdioApi,
    private val cursorProvider: () -> Pair<Int, Int>,
    private val ctx: VmContext,
) : DeviceTerminalApi {
    override fun write(text: String) {
        stdio.writeString(text)
    }

    override fun println(text: String) {
        stdio.writeString(text)
        stdio.writeString("\n")
    }

    override suspend fun readln(prompt: String): String {
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
                println = ::println,
                setCursor = ::setCursor,
                currentCursor = cursorProvider,
                // no-op: setCursor already updates all buffers via stdio
                updateCursor = { _, _ -> },
            ).readln(prompt)
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
