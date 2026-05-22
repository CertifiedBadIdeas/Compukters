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

package ru.lazyhat.compukterkraft.lang.runtime

/**
 * A fixed-size grid of characters with foreground/background colour indices.
 *
 * ## Thread-safety contract
 * - **Writer:** the VM coroutine calls [write], [println], [clear], [setCursor], [scroll].
 *   All writes happen on a single coroutine — no internal synchronisation is needed for mutations.
 * - **Reader:** the server tick thread calls [snapshot] to obtain an immutable [ScreenBufferSnapshot].
 *   [snapshot] performs a volatile read of [dirty] and, when true, copies the backing arrays under
 *   a short `synchronized` block, then clears the flag. This is the only cross-thread operation.
 */
class ScreenBuffer(
    val width: Int,
    val height: Int,
    val colour: Boolean,
) {
    /** Flat character grid — row-major, size = width × height. */
    private val chars = CharArray(width * height) { ' ' }

    /** Foreground colour index per cell (0–15). */
    private val fgColours = ByteArray(width * height) { DEFAULT_FG.toByte() }

    /** Background colour index per cell (0–15). */
    private val bgColours = ByteArray(width * height) { DEFAULT_BG.toByte() }

    /** Current cursor column. */
    var cursorX: Int = 0
        private set

    /** Current cursor row. */
    var cursorY: Int = 0
        private set

    /** Whether the cursor should blink. */
    var cursorBlink: Boolean = false
        private set

    /** Current foreground colour index for new writes. */
    var currentFg: Int = DEFAULT_FG
        private set

    /** Current background colour index for new writes. */
    var currentBg: Int = DEFAULT_BG
        private set

    /** Set to `true` on every mutation; cleared by [snapshot]. */
    @Volatile
    private var dirty: Boolean = true

    // ── Write operations (VM coroutine thread) ──────────────────────

    /**
     * Write [text] at the current cursor position, advancing the cursor.
     * Characters that fall outside the screen width are silently clipped.
     */
    fun write(text: String) {
        val y = cursorY
        if (y !in 0 until height) return
        for (ch in text) {
            val x = cursorX
            if (x in 0 until width) {
                val idx = y * width + x
                chars[idx] = ch
                fgColours[idx] = currentFg.toByte()
                bgColours[idx] = currentBg.toByte()
            }
            cursorX++
        }
        dirty = true
    }

    /**
     * Write [text] then move to the beginning of the next line.
     * If the cursor is already on the last row, the screen scrolls up by one line.
     */
    fun println(text: String) {
        write(text.take(width - cursorX))
        if (cursorY >= height - 1) {
            scroll(1)
            cursorX = 0
            cursorY = height - 1
        } else {
            cursorX = 0
            cursorY++
        }
        dirty = true
    }

    /**
     * Clear the entire screen and reset cursor to (0, 0).
     */
    fun clear() {
        chars.fill(' ')
        fgColours.fill(currentFg.toByte())
        bgColours.fill(currentBg.toByte())
        cursorX = 0
        cursorY = 0
        dirty = true
    }

    /**
     * Move the cursor to ([x], [y]) without writing anything.
     */
    fun setCursor(
        x: Int,
        y: Int,
    ) {
        cursorX = x
        cursorY = y
        dirty = true
    }

    /**
     * Set cursor blink mode.
     */
    fun setCursorBlink(blink: Boolean) {
        cursorBlink = blink
        dirty = true
    }

    /**
     * Set the foreground colour for subsequent writes.
     */
    fun setForegroundColour(colour: Int) {
        currentFg = colour.coerceIn(0, 15)
    }

    /**
     * Set the background colour for subsequent writes.
     */
    fun setBackgroundColour(colour: Int) {
        currentBg = colour.coerceIn(0, 15)
    }

    /**
     * Scroll the screen by [lines] rows (positive = up).
     * New rows are filled with spaces using the current colours.
     */
    fun scroll(lines: Int) {
        if (lines == 0) return
        if (lines > 0) {
            // Scroll up
            val shift = lines.coerceAtMost(height)
            System.arraycopy(chars, shift * width, chars, 0, (height - shift) * width)
            System.arraycopy(fgColours, shift * width, fgColours, 0, (height - shift) * width)
            System.arraycopy(bgColours, shift * width, bgColours, 0, (height - shift) * width)
            for (row in (height - shift) until height) {
                val base = row * width
                chars.fill(' ', base, base + width)
                fgColours.fill(currentFg.toByte(), base, base + width)
                bgColours.fill(currentBg.toByte(), base, base + width)
            }
        } else {
            // Scroll down
            val shift = (-lines).coerceAtMost(height)
            System.arraycopy(chars, 0, chars, shift * width, (height - shift) * width)
            System.arraycopy(fgColours, 0, fgColours, shift * width, (height - shift) * width)
            System.arraycopy(bgColours, 0, bgColours, shift * width, (height - shift) * width)
            for (row in 0 until shift) {
                val base = row * width
                chars.fill(' ', base, base + width)
                fgColours.fill(currentFg.toByte(), base, base + width)
                bgColours.fill(currentBg.toByte(), base, base + width)
            }
        }
        dirty = true
    }

    /**
     * Reset the screen to its initial state: clear all cells, reset cursor and colours.
     */
    fun reset() {
        currentFg = DEFAULT_FG
        currentBg = DEFAULT_BG
        cursorBlink = false
        clear()
    }

    // ── Snapshot (server tick thread) ───────────────────────────────

    /**
     * Take an immutable snapshot of the current screen state if anything has changed.
     * Returns `null` if the screen has not been modified since the last snapshot.
     *
     * This is the **only** method called from a different thread (server tick).
     */
    fun snapshot(): ScreenBufferSnapshot? {
        if (!dirty) return null
        synchronized(this) {
            if (!dirty) return null
            val snap =
                ScreenBufferSnapshot(
                    width = width,
                    height = height,
                    colour = colour,
                    cursorX = cursorX,
                    cursorY = cursorY,
                    cursorBlink = cursorBlink,
                    currentFg = currentFg,
                    currentBg = currentBg,
                    chars = chars.copyOf(),
                    fgColours = fgColours.copyOf(),
                    bgColours = bgColours.copyOf(),
                )
            dirty = false
            return snap
        }
    }

    /**
     * Force a snapshot regardless of dirty state. Used for initial sync when a player opens the GUI.
     */
    fun forceSnapshot(): ScreenBufferSnapshot {
        synchronized(this) {
            dirty = false
            return ScreenBufferSnapshot(
                width = width,
                height = height,
                colour = colour,
                cursorX = cursorX,
                cursorY = cursorY,
                cursorBlink = cursorBlink,
                currentFg = currentFg,
                currentBg = currentBg,
                chars = chars.copyOf(),
                fgColours = fgColours.copyOf(),
                bgColours = bgColours.copyOf(),
            )
        }
    }

    companion object {
        /** Default foreground colour index (white). */
        const val DEFAULT_FG: Int = 0

        /** Default background colour index (black). */
        const val DEFAULT_BG: Int = 15
    }
}

/**
 * An immutable snapshot of [ScreenBuffer] state, safe to read from any thread.
 * Sent over the network from server to client, and used by the renderer.
 */
data class ScreenBufferSnapshot(
    val width: Int,
    val height: Int,
    val colour: Boolean,
    val cursorX: Int,
    val cursorY: Int,
    val cursorBlink: Boolean,
    val currentFg: Int,
    val currentBg: Int,
    val chars: CharArray,
    val fgColours: ByteArray,
    val bgColours: ByteArray,
) {
    /** Get the character at grid position ([x], [y]). */
    fun charAt(
        x: Int,
        y: Int,
    ): Char = chars[y * width + x]

    /** Get the foreground colour index at grid position ([x], [y]). */
    fun fgAt(
        x: Int,
        y: Int,
    ): Int = fgColours[y * width + x].toInt() and 0xFF

    /** Get the background colour index at grid position ([x], [y]). */
    fun bgAt(
        x: Int,
        y: Int,
    ): Int = bgColours[y * width + x].toInt() and 0xFF

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenBufferSnapshot) return false
        return width == other.width && height == other.height && colour == other.colour &&
            cursorX == other.cursorX && cursorY == other.cursorY &&
            cursorBlink == other.cursorBlink &&
            currentFg == other.currentFg && currentBg == other.currentBg &&
            chars.contentEquals(other.chars) &&
            fgColours.contentEquals(other.fgColours) &&
            bgColours.contentEquals(other.bgColours)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + chars.contentHashCode()
        return result
    }

    companion object {
        /** Create an empty snapshot (used as a fallback on the client before first sync). */
        fun empty(
            width: Int,
            height: Int,
            colour: Boolean,
        ): ScreenBufferSnapshot =
            ScreenBufferSnapshot(
                width = width,
                height = height,
                colour = colour,
                cursorX = 0,
                cursorY = 0,
                cursorBlink = false,
                currentFg = ScreenBuffer.DEFAULT_FG,
                currentBg = ScreenBuffer.DEFAULT_BG,
                chars = CharArray(width * height) { ' ' },
                fgColours = ByteArray(width * height) { ScreenBuffer.DEFAULT_FG.toByte() },
                bgColours = ByteArray(width * height) { ScreenBuffer.DEFAULT_BG.toByte() },
            )
    }
}
