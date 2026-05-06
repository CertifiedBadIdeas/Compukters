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
    data class Region(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    data class FrontSnapshot(
        val version: Long,
        val regions: List<Region>,
        val pixels: IntArray,
    )

    private val front = IntArray(width * height) { OPAQUE_BLACK }
    private val staging = IntArray(width * height) { OPAQUE_BLACK }
    private val pendingDirtyRegions = mutableListOf<Region>()
    private val swappedDirtyRegions = mutableListOf<Region>()
    private var expectedSequence: Long = 1
    private var dirty = false
    var frontVersion: Long = 0
        private set
    var hasReceivedFrames: Boolean = false
        private set

    @Synchronized
    fun apply(frame: DisplayFrameDelta): Boolean {
        if (frame.displayId != displayId || frame.width != width || frame.height != height) return false
        if (frame.pixelFormat != DisplayPixelFormat.RGB565) return false
        if (!frame.fullRefresh && frame.sequence != expectedSequence) return false
        if (frame.fullRefresh) {
            staging.fill(OPAQUE_BLACK)
            pendingDirtyRegions.clear()
            pendingDirtyRegions.add(Region(0, 0, width, height))
        }
        for (tile in frame.tiles) {
            var offset = 0
            for (row in tile.y until tile.y + tile.height) {
                for (col in tile.x until tile.x + tile.width) {
                    val hi = tile.payload[offset++].toInt() and 0xFF
                    val lo = tile.payload[offset++].toInt() and 0xFF
                    staging[row * width + col] = rgb565ToArgb((hi shl 8) or lo)
                }
            }
            if (!frame.fullRefresh) {
                pendingDirtyRegions.add(Region(tile.x, tile.y, tile.width, tile.height))
            }
        }
        expectedSequence = frame.sequence + 1
        hasReceivedFrames = true
        dirty = true
        return true
    }

    @Synchronized
    fun swapIfDirty(): Boolean {
        if (!dirty) return false
        for (region in pendingDirtyRegions) {
            copyRegion(staging, front, region)
        }
        swappedDirtyRegions.clear()
        swappedDirtyRegions.addAll(pendingDirtyRegions)
        pendingDirtyRegions.clear()
        dirty = false
        frontVersion = frontVersion + 1
        return true
    }

    @Synchronized
    fun frontArgb(): IntArray = front.copyOf()

    @Synchronized
    fun frontDirtyRegions(): List<Region> = swappedDirtyRegions.toList()

    @Synchronized
    fun copyFrontSnapshotSince(uploadedVersion: Long): FrontSnapshot {
        val regions =
            if (frontVersion != uploadedVersion + 1) {
                listOf(Region(0, 0, width, height))
            } else {
                swappedDirtyRegions.ifEmpty { listOf(Region(0, 0, width, height)) }
            }
        return FrontSnapshot(frontVersion, regions, front.copyOf())
    }

    @Synchronized
    fun copyFrontArgbRegion(
        region: Region,
        destination: IntArray,
    ) {
        require(region.x >= 0 && region.y >= 0 && region.x + region.width <= width && region.y + region.height <= height)
        require(destination.size >= region.width * region.height)
        var destinationOffset = 0
        var row = region.y
        while (row < region.y + region.height) {
            front.copyInto(destination, destinationOffset, row * width + region.x, row * width + region.x + region.width)
            destinationOffset = destinationOffset + region.width
            row = row + 1
        }
    }

    @Synchronized
    fun copyFrontArgbRow(
        x: Int,
        y: Int,
        width: Int,
        destination: IntArray,
    ) {
        require(x >= 0 && y >= 0 && x + width <= this.width && y < height)
        require(destination.size >= width)
        front.copyInto(destination, 0, y * this.width + x, y * this.width + x + width)
    }

    private fun copyRegion(
        source: IntArray,
        destination: IntArray,
        region: Region,
    ) {
        var row = region.y
        while (row < region.y + region.height) {
            source.copyInto(destination, row * width + region.x, row * width + region.x, row * width + region.x + region.width)
            row = row + 1
        }
    }

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
