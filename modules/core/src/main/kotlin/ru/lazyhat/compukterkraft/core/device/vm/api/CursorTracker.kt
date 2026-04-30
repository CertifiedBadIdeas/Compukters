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

import ru.lazyhat.compukterkraft.lang.runtime.vt.VtSink

/**
 * Lightweight [VtSink] that tracks only the cursor position on an unbounded
 * abstract grid. Used by [VmTerminalApi.readln] after the server-side
 * [ScreenBuffer][ru.lazyhat.compukterkraft.core.device.vm.api.ScreenBuffer]
 * was removed — we still need to know how far the cursor advanced while
 * printing a prompt, but no actual buffer is maintained.
 *
 * Coordinates are 0-based. There is no clamping; X can grow unboundedly
 * until a CR/LF resets it.
 */
class CursorTracker : VtSink {
    var cursorX: Int = 0
        private set
    var cursorY: Int = 0
        private set

    private var savedX: Int = 0
    private var savedY: Int = 0

    override fun printChar(ch: Char) {
        cursorX += 1
    }

    override fun moveCursor(
        row: Int?,
        col: Int?,
    ) {
        cursorY = (row ?: 1) - 1
        cursorX = (col ?: 1) - 1
    }

    override fun cursorRelative(
        deltaRows: Int,
        deltaCols: Int,
    ) {
        cursorY += deltaRows
        cursorX += deltaCols
        if (cursorX < 0) cursorX = 0
        if (cursorY < 0) cursorY = 0
    }

    override fun eraseDisplay(mode: Int) = Unit

    override fun eraseLine(mode: Int) = Unit

    override fun setForegroundColor(color: Int) = Unit

    override fun setBackgroundColor(color: Int) = Unit

    override fun resetAttributes() = Unit

    override fun saveCursor() {
        savedX = cursorX
        savedY = cursorY
    }

    override fun restoreCursor() {
        cursorX = savedX
        cursorY = savedY
    }

    override fun lineFeed() {
        // Match ScreenBufferVtSink: LF behaves as CR+LF (move to column 0 of
        // the next row). This is what the VM's client-side ScreenBuffer does,
        // so the tracker must follow suit — otherwise readln's cursor
        // arithmetic drifts after any println and backspace emits CSI H
        // coordinates that send the cursor into unrelated lines of the log.
        cursorY += 1
        cursorX = 0
    }

    override fun carriageReturn() {
        cursorX = 0
    }

    override fun backspace() {
        if (cursorX > 0) cursorX -= 1
    }
}
