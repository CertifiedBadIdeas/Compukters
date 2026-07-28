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

package ru.lazyhat.compukterkraft.lang.runtime.display

enum class DisplayPixelFormat {
    RGB565,
}

data class DisplayInfo(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val pixelFormat: DisplayPixelFormat,
)

data class DisplayTile(
    val tileX: Int,
    val tileY: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DisplayTile &&
            tileX == other.tileX &&
            tileY == other.tileY &&
            x == other.x &&
            y == other.y &&
            width == other.width &&
            height == other.height &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = tileX
        result = 31 * result + tileY
        result = 31 * result + x
        result = 31 * result + y
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

sealed interface DisplayFrameOperation {
    data class FillRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val rgb565: Int,
    ) : DisplayFrameOperation

    data class CopyRect(
        val srcX: Int,
        val srcY: Int,
        val width: Int,
        val height: Int,
        val dstX: Int,
        val dstY: Int,
    ) : DisplayFrameOperation

    data class MonoBlit(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val foregroundRgb565: Int,
        val backgroundRgb565: Int,
        val packedMask: ByteArray,
    ) : DisplayFrameOperation {
        override fun equals(other: Any?): Boolean =
            other is MonoBlit &&
                x == other.x &&
                y == other.y &&
                width == other.width &&
                height == other.height &&
                foregroundRgb565 == other.foregroundRgb565 &&
                backgroundRgb565 == other.backgroundRgb565 &&
                packedMask.contentEquals(other.packedMask)

        override fun hashCode(): Int {
            var result = x
            result = 31 * result + y
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + foregroundRgb565
            result = 31 * result + backgroundRgb565
            result = 31 * result + packedMask.contentHashCode()
            return result
        }
    }
}

data class DisplayFrameDelta(
    val displayId: Int,
    val sequence: Long,
    val width: Int,
    val height: Int,
    val pixelFormat: DisplayPixelFormat,
    val fullRefresh: Boolean,
    val tiles: List<DisplayTile>,
    val operations: List<DisplayFrameOperation> = emptyList(),
)
