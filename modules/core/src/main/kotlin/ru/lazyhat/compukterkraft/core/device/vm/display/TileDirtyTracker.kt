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

data class DirtyTile(
    val tileX: Int,
    val tileY: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

class TileDirtyTracker(
    private val width: Int,
    private val height: Int,
    private val tileSize: Int = DEFAULT_TILE_SIZE,
) {
    private val tilesX = ((width + tileSize - 1) / tileSize).coerceAtLeast(0)
    private val tilesY = ((height + tileSize - 1) / tileSize).coerceAtLeast(0)
    private val dirty = BooleanArray(tilesX * tilesY)

    fun markRectDirty(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val minX = x.coerceAtLeast(0)
        val minY = y.coerceAtLeast(0)
        val maxX = (x + width - 1).coerceAtMost(this.width - 1)
        val maxY = (y + height - 1).coerceAtMost(this.height - 1)
        if (minX > maxX || minY > maxY) return

        val startTileX = minX / tileSize
        val endTileX = maxX / tileSize
        val startTileY = minY / tileSize
        val endTileY = maxY / tileSize
        for (tileY in startTileY..endTileY) {
            for (tileX in startTileX..endTileX) {
                dirty[tileY * tilesX + tileX] = true
            }
        }
    }

    fun markAllDirty() {
        dirty.fill(true)
    }

    fun dirtyTiles(): List<DirtyTile> =
        buildList {
            for (tileY in 0 until tilesY) {
                for (tileX in 0 until tilesX) {
                    if (!dirty[tileY * tilesX + tileX]) continue
                    val x = tileX * tileSize
                    val y = tileY * tileSize
                    add(
                        DirtyTile(
                            tileX = tileX,
                            tileY = tileY,
                            x = x,
                            y = y,
                            width = minOf(tileSize, width - x),
                            height = minOf(tileSize, height - y),
                        ),
                    )
                }
            }
        }

    fun clear() {
        dirty.fill(false)
    }

    companion object {
        const val DEFAULT_TILE_SIZE = 16
    }
}
