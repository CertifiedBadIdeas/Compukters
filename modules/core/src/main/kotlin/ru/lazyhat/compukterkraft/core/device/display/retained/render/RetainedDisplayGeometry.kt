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

package ru.lazyhat.compukterkraft.core.device.display.retained.render

data class RetainedFloatRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class RetainedIntegerRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun intersection(other: RetainedIntegerRect): RetainedIntegerRect? {
        val result =
            RetainedIntegerRect(
                maxOf(left, other.left),
                maxOf(top, other.top),
                minOf(right, other.right),
                minOf(bottom, other.bottom),
            )
        return result.takeIf { it.width > 0 && it.height > 0 }
    }

    fun contains(other: RetainedIntegerRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun overlaps(other: RetainedIntegerRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun toFloatRect(): RetainedFloatRect =
        RetainedFloatRect(left.toFloat(), top.toFloat(), width.toFloat(), height.toFloat())

    companion object {
        val EMPTY = RetainedIntegerRect(0, 0, 0, 0)

        fun from(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ): RetainedIntegerRect = RetainedIntegerRect(x, y, x + width, y + height)
    }
}

data class RetainedQuad(
    val destination: RetainedFloatRect,
    val sourceUv: RetainedFloatRect?,
    val argb: Int,
    val textureIdentity: Long?,
)

data class RetainedGeometryBatch(
    val textureIdentity: Long?,
    val quads: List<RetainedQuad>,
)

data class RetainedInstanceChunkKey(
    val maskIdentity: Long,
    val instanceBufferIdentity: Long,
    val firstInstance: Int,
)

data class RetainedInstanceChunk(
    val key: RetainedInstanceChunkKey,
    val batches: List<RetainedGeometryBatch>,
)

sealed interface RetainedInstanceSpan {
    data class Cached(
        val key: RetainedInstanceChunkKey,
        val translationX: Int,
        val translationY: Int,
    ) : RetainedInstanceSpan

    data class Direct(
        val firstInstance: Int,
        val instanceCount: Int,
        val maskIdentity: Long,
        val instanceBufferIdentity: Long,
        val translationX: Int,
        val translationY: Int,
        val clip: RetainedIntegerRect,
        val batches: List<RetainedGeometryBatch>,
    ) : RetainedInstanceSpan
}

sealed interface RetainedCompiledCommand {
    data class Direct(
        val batches: List<RetainedGeometryBatch>,
    ) : RetainedCompiledCommand

    data class InstanceRange(
        val spans: List<RetainedInstanceSpan>,
    ) : RetainedCompiledCommand
}

data class RetainedCompiledPresentation(
    val background: RetainedGeometryBatch,
    val commands: List<RetainedCompiledCommand>,
    val instanceChunks: Map<RetainedInstanceChunkKey, RetainedInstanceChunk>,
)

fun retainedRgb565ToArgb(rgb565: Int): Int {
    val red5 = rgb565 ushr 11 and 0x1f
    val green6 = rgb565 ushr 5 and 0x3f
    val blue5 = rgb565 and 0x1f
    val red8 = red5 shl 3 or (red5 ushr 2)
    val green8 = green6 shl 2 or (green6 ushr 4)
    val blue8 = blue5 shl 3 or (blue5 ushr 2)
    return 0xff00_0000.toInt() or (red8 shl 16) or (green8 shl 8) or blue8
}
