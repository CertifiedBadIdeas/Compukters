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

    fun copyTile(tile: DirtyTile): ByteArray {
        val out = ByteArray(tile.width * tile.height * BYTES_PER_PIXEL)
        var offset = 0
        for (row in tile.y until tile.y + tile.height) {
            for (col in tile.x until tile.x + tile.width) {
                val value = pixels[row * width + col].toInt() and 0xFFFF
                out[offset++] = (value ushr 8).toByte()
                out[offset++] = value.toByte()
            }
        }
        return out
    }

    fun copyFrom(other: PixelBuffer) {
        require(width == other.width && height == other.height) { "Pixel buffer sizes differ" }
        other.pixels.copyInto(pixels)
    }

    companion object {
        const val BYTES_PER_PIXEL = 2
    }
}
