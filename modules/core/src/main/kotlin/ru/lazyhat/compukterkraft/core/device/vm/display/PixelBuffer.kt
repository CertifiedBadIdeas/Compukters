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

data class TileCopyResult(
    val payload: ByteArray,
    val nanos: Long,
)

class PixelBuffer(
    val width: Int,
    val height: Int,
) {
    private val pixels = ShortArray(width * height)

    fun clear(rgb565: Int) {
        pixels.fill(rgb565.toShort())
    }

    fun setPixel(
        x: Int,
        y: Int,
        rgb565: Int,
    ) {
        if (x !in 0 until width || y !in 0 until height) return
        pixels[y * width + x] = rgb565.toShort()
    }

    fun fillRect(
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int,
        rgb565: Int,
    ) {
        if (rectWidth <= 0 || rectHeight <= 0) return
        val minX = x.coerceAtLeast(0)
        val minY = y.coerceAtLeast(0)
        val maxX = (x + rectWidth).coerceAtMost(width)
        val maxY = (y + rectHeight).coerceAtMost(height)
        for (row in minY until maxY) {
            val base = row * width
            for (col in minX until maxX) {
                pixels[base + col] = rgb565.toShort()
            }
        }
    }

    fun copyRect(
        srcX: Int,
        srcY: Int,
        rectWidth: Int,
        rectHeight: Int,
        dstX: Int,
        dstY: Int,
    ) {
        if (rectWidth <= 0 || rectHeight <= 0) return

        var sourceX = srcX
        var sourceY = srcY
        var targetX = dstX
        var targetY = dstY
        var copyWidth = rectWidth
        var copyHeight = rectHeight

        if (sourceX < 0) {
            val delta = -sourceX
            sourceX = 0
            targetX += delta
            copyWidth -= delta
        }
        if (sourceY < 0) {
            val delta = -sourceY
            sourceY = 0
            targetY += delta
            copyHeight -= delta
        }
        if (targetX < 0) {
            val delta = -targetX
            targetX = 0
            sourceX += delta
            copyWidth -= delta
        }
        if (targetY < 0) {
            val delta = -targetY
            targetY = 0
            sourceY += delta
            copyHeight -= delta
        }

        copyWidth = minOf(copyWidth, width - sourceX, width - targetX)
        copyHeight = minOf(copyHeight, height - sourceY, height - targetY)
        if (copyWidth <= 0 || copyHeight <= 0) return

        val tmp = ShortArray(copyWidth * copyHeight)
        var offset = 0
        for (row in 0 until copyHeight) {
            val sourceBase = (sourceY + row) * width + sourceX
            for (col in 0 until copyWidth) {
                tmp[offset++] = pixels[sourceBase + col]
            }
        }

        offset = 0
        for (row in 0 until copyHeight) {
            val targetBase = (targetY + row) * width + targetX
            for (col in 0 until copyWidth) {
                pixels[targetBase + col] = tmp[offset++]
            }
        }
    }

    fun blitMono(
        x: Int,
        y: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        mask: String,
        foreground: Int,
        background: Int,
    ) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return
        for (row in 0 until bitmapHeight) {
            val targetY = y + row
            if (targetY !in 0 until height) continue
            for (col in 0 until bitmapWidth) {
                val targetX = x + col
                if (targetX !in 0 until width) continue
                val maskIndex = row * bitmapWidth + col
                val bit = if (maskIndex < mask.length) mask[maskIndex] else '0'
                if (bit == '1') {
                    pixels[targetY * width + targetX] = foreground.toShort()
                } else if (background >= 0) {
                    pixels[targetY * width + targetX] = background.toShort()
                }
            }
        }
    }

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
        val rows = intArrayOf(row0, row1, row2, row3, row4, row5, row6)
        for (row in 0 until 7) {
            val targetY = y + row
            if (targetY !in 0 until height) continue
            val bits = rows[row] and 0b11111
            for (col in 0 until 5) {
                val targetX = x + col
                if (targetX !in 0 until width) continue
                val isForeground = bits and (1 shl (4 - col)) != 0
                if (isForeground) {
                    pixels[targetY * width + targetX] = foreground.toShort()
                } else if (background >= 0) {
                    pixels[targetY * width + targetX] = background.toShort()
                }
            }
        }
    }

    fun blitMono5x7Text(
        x: Int,
        y: Int,
        text: String,
        foreground: Int,
        background: Int,
    ) {
        text.forEachIndexed { index, ch ->
            val glyphX = x + index * Mono5x7Font.CELL_ADVANCE
            blitMono5x7(
                glyphX,
                y,
                Mono5x7Font.rowBits(ch, 0),
                Mono5x7Font.rowBits(ch, 1),
                Mono5x7Font.rowBits(ch, 2),
                Mono5x7Font.rowBits(ch, 3),
                Mono5x7Font.rowBits(ch, 4),
                Mono5x7Font.rowBits(ch, 5),
                Mono5x7Font.rowBits(ch, 6),
                foreground,
                background,
            )
        }
    }

    fun copyTile(tile: DirtyTile): ByteArray = copyTileWithMetrics(tile).payload

    fun copyTileWithMetrics(tile: DirtyTile): TileCopyResult {
        val started = System.nanoTime()
        val out = ByteArray(tile.width * tile.height * BYTES_PER_PIXEL)
        var offset = 0
        for (row in tile.y until tile.y + tile.height) {
            for (col in tile.x until tile.x + tile.width) {
                val value = pixels[row * width + col].toInt() and 0xFFFF
                out[offset++] = (value ushr 8).toByte()
                out[offset++] = value.toByte()
            }
        }
        return TileCopyResult(out, System.nanoTime() - started)
    }

    fun copyFrom(other: PixelBuffer) {
        require(width == other.width && height == other.height) { "Pixel buffer sizes differ" }
        other.pixels.copyInto(pixels)
    }

    companion object {
        const val BYTES_PER_PIXEL = 2
    }
}
