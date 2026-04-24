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
 * Mutation target for the VT-100 parser.
 *
 * Implementations take high-level terminal operations and apply them to their
 * backing store (ScreenBuffer on the server in Epic 1; a client-side buffer
 * in later epics). All row/column values are 1-based to match the VT-100
 * wire format; conversion to 0-based project coordinates lives inside the
 * sink implementation.
 */
interface VtSink {
    fun printChar(ch: Char)

    /** CSI `H` / `f`. A null component means "keep current value / default to 1". */
    fun moveCursor(
        row: Int?,
        col: Int?,
    )

    /** CSI `A`/`B`/`C`/`D`. Deltas are signed: negative = up/left. */
    fun cursorRelative(
        deltaRows: Int,
        deltaCols: Int,
    )

    /** CSI `J`. Mode: 0 = from cursor down, 1 = from cursor up, 2 = whole screen. */
    fun eraseDisplay(mode: Int)

    /** CSI `K`. Mode: 0 = from cursor to EOL, 1 = from BOL to cursor, 2 = entire line. */
    fun eraseLine(mode: Int)

    /** CSI `m` with codes 30..37 / 90..97. Color indexes are 0..15. */
    fun setForegroundColor(color: Int)

    /** CSI `m` with codes 40..47 / 100..107. */
    fun setBackgroundColor(color: Int)

    /** CSI `m` with code 0. */
    fun resetAttributes()

    /** CSI `s`. */
    fun saveCursor()

    /** CSI `u`. */
    fun restoreCursor()

    /** Raw `\n`. */
    fun lineFeed()

    /** Raw `\r`. */
    fun carriageReturn()

    /** Raw `\b`: cursor moves left, no erase. */
    fun backspace()
}
