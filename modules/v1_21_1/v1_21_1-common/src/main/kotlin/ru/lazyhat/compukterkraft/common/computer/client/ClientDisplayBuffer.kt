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

package ru.lazyhat.compukterkraft.common.computer.client

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat

class ClientDisplayBuffer(
    val displayId: Int,
    val width: Int,
    val height: Int,
) {
    private val front = IntArray(width * height) { OPAQUE_BLACK }
    private val staging = IntArray(width * height) { OPAQUE_BLACK }
    private var expectedSequence: Long = 1
    private var dirty = false

    fun apply(frame: DisplayFrameDelta): Boolean {
        if (frame.displayId != displayId || frame.width != width || frame.height != height) return false
        if (frame.pixelFormat != DisplayPixelFormat.RGB565) return false
        if (!frame.fullRefresh && frame.sequence != expectedSequence) return false
        if (frame.fullRefresh) staging.fill(OPAQUE_BLACK)
        for (tile in frame.tiles) {
            var offset = 0
            for (row in tile.y until tile.y + tile.height) {
                for (col in tile.x until tile.x + tile.width) {
                    val hi = tile.payload[offset++].toInt() and 0xFF
                    val lo = tile.payload[offset++].toInt() and 0xFF
                    staging[row * width + col] = rgb565ToArgb((hi shl 8) or lo)
                }
            }
        }
        expectedSequence = frame.sequence + 1
        dirty = true
        return true
    }

    fun swapIfDirty(): Boolean {
        if (!dirty) return false
        staging.copyInto(front)
        dirty = false
        return true
    }

    fun frontArgb(): IntArray = front.copyOf()

    private fun rgb565ToArgb(value: Int): Int {
        val r5 = (value ushr 11) and 0x1F
        val g6 = (value ushr 5) and 0x3F
        val b5 = value and 0x1F
        val r = (r5 shl 3) or (r5 ushr 2)
        val g = (g6 shl 2) or (g6 ushr 4)
        val b = (b5 shl 3) or (b5 ushr 2)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        private const val OPAQUE_BLACK = -0x1000000
    }
}