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

data class DisplayFrameBuildResult(
    val frame: DisplayFrameDelta,
    val metrics: DisplayFrameBuildMetrics,
)

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
    fun copyRect(
        srcX: Int,
        srcY: Int,
        width: Int,
        height: Int,
        dstX: Int,
        dstY: Int,
    ) {
        back.copyRect(srcX, srcY, width, height, dstX, dstY)
        dirty.markRectDirty(dstX, dstY, width, height)
    }

    @Synchronized
    fun blitMono(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: String,
        foreground: Int,
        background: Int,
    ) {
        back.blitMono(x, y, width, height, mask, foreground, background)
        dirty.markRectDirty(x, y, width, height)
    }

    @Synchronized
    fun blitMono5x7(
        x: Int,
        y: Int,
        row0: Int,
        row1: Int,
        row2: Int,
        row3: Int,
        row4: Int,
        row5: Int,
        row6: Int,
        foreground: Int,
        background: Int,
    ) {
        back.blitMono5x7(x, y, row0, row1, row2, row3, row4, row5, row6, foreground, background)
        dirty.markRectDirty(x, y, 5, 7)
    }

    @Synchronized
    fun present(): DisplayFrameDelta? = presentWithMetrics()?.frame

    @Synchronized
    fun presentWithMetrics(): DisplayFrameBuildResult? {
        val totalStarted = System.nanoTime()
        val dirtyStarted = System.nanoTime()
        val dirtyTiles = dirty.dirtyTiles()
        val dirtyNanos = System.nanoTime() - dirtyStarted
        if (dirtyTiles.isEmpty()) return null
        sequence += 1
        val frameStarted = System.nanoTime()
        val frame = buildFrameWithMetrics(dirtyTiles, fullRefresh = false)
        val frameBuildNanos = System.nanoTime() - frameStarted
        val copyStarted = System.nanoTime()
        front.copyFrom(back)
        val frontCopyNanos = System.nanoTime() - copyStarted
        dirty.clear()
        return DisplayFrameBuildResult(
            frame = frame.frame,
            metrics =
                DisplayFrameBuildMetrics(
                    dirtyTileScanNanos = dirtyNanos,
                    frameBuildNanos = frameBuildNanos,
                    tileSerializationNanos = frame.tileSerializationNanos,
                    frontCopyNanos = frontCopyNanos,
                    totalNanos = System.nanoTime() - totalStarted,
                    tileCount = frame.frame.tiles.size.toLong(),
                    payloadBytes = frame.frame.tiles.sumOf { it.payload.size }.toLong(),
                ),
        )
    }

    @Synchronized
    fun fullRefresh(): DisplayFrameDelta = fullRefreshWithMetrics().frame

    @Synchronized
    fun fullRefreshWithMetrics(): DisplayFrameBuildResult {
        dirty.markAllDirty()
        return presentWithMetrics(fullRefresh = true) ?: error("Full refresh should always produce a frame")
    }

    private fun presentWithMetrics(fullRefresh: Boolean): DisplayFrameBuildResult? {
        val totalStarted = System.nanoTime()
        val dirtyStarted = System.nanoTime()
        val dirtyTiles = dirty.dirtyTiles()
        val dirtyNanos = System.nanoTime() - dirtyStarted
        if (dirtyTiles.isEmpty()) return null
        sequence += 1
        val frameStarted = System.nanoTime()
        val frame = buildFrameWithMetrics(dirtyTiles, fullRefresh = fullRefresh)
        val frameBuildNanos = System.nanoTime() - frameStarted
        val copyStarted = System.nanoTime()
        front.copyFrom(back)
        val frontCopyNanos = System.nanoTime() - copyStarted
        dirty.clear()
        return DisplayFrameBuildResult(
            frame = frame.frame,
            metrics =
                DisplayFrameBuildMetrics(
                    dirtyTileScanNanos = dirtyNanos,
                    frameBuildNanos = frameBuildNanos,
                    tileSerializationNanos = frame.tileSerializationNanos,
                    frontCopyNanos = frontCopyNanos,
                    totalNanos = System.nanoTime() - totalStarted,
                    tileCount = frame.frame.tiles.size.toLong(),
                    payloadBytes = frame.frame.tiles.sumOf { it.payload.size }.toLong(),
                ),
        )
    }

    private data class BuiltFrame(
        val frame: DisplayFrameDelta,
        val tileSerializationNanos: Long,
    )

    private fun buildFrame(
        tiles: List<DirtyTile>,
        fullRefresh: Boolean,
    ): DisplayFrameDelta = buildFrameWithMetrics(tiles, fullRefresh).frame

    private fun buildFrameWithMetrics(
        tiles: List<DirtyTile>,
        fullRefresh: Boolean,
    ): BuiltFrame {
        var tileSerializationNanos = 0L
        val displayTiles =
            tiles.map { tile ->
                val copied = back.copyTileWithMetrics(tile)
                tileSerializationNanos += copied.nanos
                DisplayTile(
                    tileX = tile.tileX,
                    tileY = tile.tileY,
                    x = tile.x,
                    y = tile.y,
                    width = tile.width,
                    height = tile.height,
                    payload = copied.payload,
                )
            }
        return BuiltFrame(
            frame =
                DisplayFrameDelta(
                    displayId = displayId,
                    sequence = sequence,
                    width = width,
                    height = height,
                    pixelFormat = pixelFormat,
                    fullRefresh = fullRefresh,
                    tiles = displayTiles,
                ),
            tileSerializationNanos = tileSerializationNanos,
        )
    }
}
