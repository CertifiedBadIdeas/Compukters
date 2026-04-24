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

import ru.lazyhat.compukterkraft.lang.runtime.ComputerStdioApi
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import ru.lazyhat.compukterkraft.lang.runtime.vt.VtParser

/**
 * Server-side [ComputerStdioApi] for Epic 1.
 *
 * Every [writeString] call feeds the chunk into a [VtParser] whose sink is a
 * [ScreenBufferVtSink] wrapping the same [ScreenBuffer] that the old
 * [VmTerminalApi] mutated directly. Behavior is intentionally byte-for-byte
 * identical to the pre-refactor direct-mutation path.
 *
 * Later epics replace this with a broadcaster that fans the stream out to N
 * attached network sessions and tees it into a scrollback ring.
 */
class VmStdioApi(
    buffer: ScreenBuffer,
) : ComputerStdioApi {
    private val parser: VtParser = VtParser(ScreenBufferVtSink(buffer))

    override fun writeString(text: String) {
        parser.feed(text)
    }
}
