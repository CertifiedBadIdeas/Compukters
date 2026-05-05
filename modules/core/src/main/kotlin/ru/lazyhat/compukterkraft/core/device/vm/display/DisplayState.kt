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

package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile

class DisplayState(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val pixelFormat: DisplayPixelFormat,
) {
    private val back = PixelBuffer(width, height)
    private val front = PixelBuffer(width, height)
    private val dirty = TileDirtyTracker(width, height)
    private var sequence: Long = 0

    @Synchronized
    fun clear(rgb565: Int) {
        back.clear(rgb565)
        dirty.markAllDirty()
    }

    @Synchronized
    fun setPixel(
        x: Int,
        y: Int,
        rgb565: Int,
    ) {
        back.setPixel(x, y, rgb565)
        dirty.markRectDirty(x, y, 1, 1)
    }

    @Synchronized
    fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) {
        back.fillRect(x, y, width, height, rgb565)
        dirty.markRectDirty(x, y, width, height)
    }

    @Synchronized
    fun present(): DisplayFrameDelta? {
        val dirtyTiles = dirty.dirtyTiles()
        if (dirtyTiles.isEmpty()) return null
        sequence += 1
        val frame = buildFrame(dirtyTiles, fullRefresh = false)
        front.copyFrom(back)
        dirty.clear()
        return frame
    }

    @Synchronized
    fun fullRefresh(): DisplayFrameDelta {
        dirty.markAllDirty()
        sequence += 1
        val frame = buildFrame(dirty.dirtyTiles(), fullRefresh = true)
        front.copyFrom(back)
        dirty.clear()
        return frame
    }

    private fun buildFrame(
        tiles: List<DirtyTile>,
        fullRefresh: Boolean,
    ): DisplayFrameDelta =
        DisplayFrameDelta(
            displayId = displayId,
            sequence = sequence,
            width = width,
            height = height,
            pixelFormat = pixelFormat,
            fullRefresh = fullRefresh,
            tiles =
                tiles.map { tile ->
                    DisplayTile(
                        tileX = tile.tileX,
                        tileY = tile.tileY,
                        x = tile.x,
                        y = tile.y,
                        width = tile.width,
                        height = tile.height,
                        payload = back.copyTile(tile),
                    )
                },
        )
}
