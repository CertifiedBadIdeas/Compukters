/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.ide

import kotlin.math.roundToInt

internal const val IDE_SCROLLBAR_SIZE = 8

enum class IdeScrollTarget { Hover, EditorLines, EditorColumns }

data class IdeScrollbar(
    val target: IdeScrollTarget,
    val track: IdeRect,
    val position: Int,
    val maximum: Int,
    val page: Int,
) {
    val horizontal: Boolean get() = target == IdeScrollTarget.EditorColumns
    private val length: Int get() = if (horizontal) track.width else track.height
    val thumbLength: Int get() = ((length.toLong() * page / (maximum.toLong() + page)).toInt()).coerceIn(minOf(12, length), length)
    val travel: Int get() = length - thumbLength
    val start: Int get() = if (horizontal) track.left else track.top
    val thumbStart: Int get() = start + (travel.toLong() * position.coerceIn(0, maximum) / maximum.coerceAtLeast(1)).toInt()
    val thumb: IdeRect get() =
        if (horizontal) {
            IdeRect(thumbStart, track.top + 2, thumbStart + thumbLength, track.bottom - 2)
        } else {
            IdeRect(track.left + 2, thumbStart, track.right - 2, thumbStart + thumbLength)
        }

    fun coordinate(
        x: Double,
        y: Double,
    ): Double = if (horizontal) x else y

    fun positionAt(
        coordinate: Double,
        grabOffset: Double,
    ): Int = if (travel <= 0) 0 else (((coordinate - start - grabOffset) / travel).coerceIn(0.0, 1.0) * maximum).roundToInt()
}

/** Keeps pointer capture until release, including outside the original track. */
internal class IdeScrollbarInteraction {
    private var dragging: IdeScrollbar? = null
    private var grabOffset = 0.0
    val active: Boolean get() = dragging != null

    fun press(
        bars: List<IdeScrollbar>,
        x: Double,
        y: Double,
        move: (IdeScrollTarget, Int) -> Unit,
    ): Boolean {
        val bar = bars.asReversed().firstOrNull { it.track.containsPoint(x, y) } ?: return false
        val coordinate = bar.coordinate(x, y)
        if (coordinate >= bar.thumbStart && coordinate < bar.thumbStart + bar.thumbLength) {
            dragging = bar
            grabOffset = coordinate - bar.thumbStart
        } else {
            move(bar.target, (bar.position + if (coordinate < bar.thumbStart) -bar.page else bar.page).coerceIn(0, bar.maximum))
        }
        return true
    }

    fun drag(
        x: Double,
        y: Double,
        move: (IdeScrollTarget, Int) -> Unit,
    ): Boolean {
        val bar = dragging ?: return false
        if (x.isFinite() && y.isFinite()) move(bar.target, bar.positionAt(bar.coordinate(x, y), grabOffset))
        return true
    }

    fun release(): Boolean {
        val active = active
        dragging = null
        return active
    }
}
