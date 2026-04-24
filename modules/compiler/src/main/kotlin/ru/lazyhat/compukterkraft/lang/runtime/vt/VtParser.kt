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

package ru.lazyhat.compukterkraft.lang.runtime.vt

/**
 * Streaming parser for a subset of VT-100 escape sequences.
 *
 * State machine; accepts arbitrarily chunked input via [feed]. Internal state
 * is preserved across calls, so a multi-byte escape split across two feed()s
 * is handled correctly.
 *
 * Supported CSI sequences (Epic 1):
 *  - `H`, `f` — cursor position
 *  - `A`, `B`, `C`, `D` — relative cursor move
 *  - `J`, `K` — erase display / line
 *  - `m` — SGR colors (30–37, 40–47, 90–97, 100–107, 0 = reset)
 *  - `s`, `u` — save / restore cursor
 *
 * Unknown CSI terminators are silently dropped. Unknown ESC-intro bytes are
 * silently dropped. This is intentional: producers are our own stdlib, and a
 * strict parser would crash the VM on typos instead of logging.
 */
class VtParser(
    private val sink: VtSink,
) {
    private enum class State { GROUND, ESCAPE, CSI }

    private var state: State = State.GROUND
    private val csiBuffer: StringBuilder = StringBuilder()

    /**
     * DEC private-mode flag. Set when the first CSI intermediate char is `?`
     * (e.g. `CSI ? 25 h`). Consumed and cleared when the CSI terminator fires.
     */
    private var csiPrivate: Boolean = false

    fun feed(chunk: String) {
        for (ch in chunk) feedChar(ch)
    }

    private fun feedChar(ch: Char) {
        when (state) {
            State.GROUND -> ground(ch)
            State.ESCAPE -> escape(ch)
            State.CSI -> csi(ch)
        }
    }

    private fun ground(ch: Char) {
        when (ch) {
            '\u001b' -> state = State.ESCAPE
            '\n' -> sink.lineFeed()
            '\r' -> sink.carriageReturn()
            '\b' -> sink.backspace()
            else -> sink.printChar(ch)
        }
    }

    private fun escape(ch: Char) {
        when (ch) {
            '[' -> {
                state = State.CSI
                csiBuffer.clear()
                csiPrivate = false
            }
            else -> state = State.GROUND
        }
    }

    private fun csi(ch: Char) {
        if (csiBuffer.isEmpty() && !csiPrivate && ch == '?') {
            csiPrivate = true
            return
        }
        if (ch in '0'..'9' || ch == ';') {
            csiBuffer.append(ch)
            return
        }
        val params = parseParams(csiBuffer.toString())
        val isPrivate = csiPrivate
        state = State.GROUND
        csiPrivate = false
        if (isPrivate) {
            // DEC private mode set/reset. Only DECTCEM (mode 25) is supported.
            when (ch) {
                'h' -> if (params.getOrNull(0) == 25) sink.setCursorVisible(true)
                'l' -> if (params.getOrNull(0) == 25) sink.setCursorVisible(false)
                else -> Unit // unknown private mode terminator
            }
            return
        }
        when (ch) {
            'H', 'f' -> sink.moveCursor(params.getOrNull(0), params.getOrNull(1))
            'J' -> sink.eraseDisplay(params.getOrNull(0) ?: 0)
            'K' -> sink.eraseLine(params.getOrNull(0) ?: 0)
            'A' -> sink.cursorRelative(-(params.getOrNull(0) ?: 1), 0)
            'B' -> sink.cursorRelative(params.getOrNull(0) ?: 1, 0)
            'C' -> sink.cursorRelative(0, params.getOrNull(0) ?: 1)
            'D' -> sink.cursorRelative(0, -(params.getOrNull(0) ?: 1))
            's' -> sink.saveCursor()
            'u' -> sink.restoreCursor()
            'm' -> handleSgr(params)
            else -> Unit // unknown CSI terminator — drop silently
        }
    }

    private fun handleSgr(params: List<Int?>) {
        val effective = if (params.isEmpty()) listOf(0) else params.map { it ?: 0 }
        for (p in effective) {
            when (p) {
                0 -> sink.resetAttributes()
                in 30..37 -> sink.setForegroundColor(p - 30)
                in 40..47 -> sink.setBackgroundColor(p - 40)
                in 90..97 -> sink.setForegroundColor(p - 90 + 8)
                in 100..107 -> sink.setBackgroundColor(p - 100 + 8)
                // Unknown SGR param — silently drop.
            }
        }
    }

    /**
     * `"3;5"` → `[3, 5]`. Empty slot → null (means "default"). Empty raw → [].
     */
    private fun parseParams(raw: String): List<Int?> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { if (it.isEmpty()) null else it.toInt() }
    }
}
