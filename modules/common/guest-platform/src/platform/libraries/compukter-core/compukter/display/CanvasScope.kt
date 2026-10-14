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

package compukter.display

import kotlin.math.Position2i
import kotlin.math.Rect2i
import kotlin.math.Size2i
import kotlin.math.Vector2i

/** Local pixel drawing clipped to this region and its ancestors. Does not clear or publish a frame. */
public value class CanvasScope internal constructor(
    private val display: DisplayFrame,
    public val bounds: Rect2i,
    private val clip: Rect2i,
) {
    public val size: Size2i get() = bounds.size
    internal val isEmpty: Boolean get() = clip.isEmpty

    public fun pixel(x: Int, y: Int, color: Int) {
        val position = global(Vector2i(x, y))
        if (position in clip) display.pixel(position, color)
    }

    public fun pixel(position: Position2i, color: Int) { pixel(position.x, position.y, color) }

    public fun line(start: Position2i, end: Position2i, color: Int) {
        display.lineClipped(global(start), global(end), color, clip)
    }

    public fun line(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) { line(Vector2i(x0, y0), Vector2i(x1, y1), color) }

    public fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int) {
        val rectangle = Rect2i(global(Vector2i(x, y)), Vector2i(width, height)).intersection(clip)
        if (!rectangle.isEmpty) display.fillRect(rectangle, color)
    }

    public fun fillRect(bounds: Rect2i, color: Int) { fillRect(bounds.left, bounds.top, bounds.size.x, bounds.size.y, color) }

    public fun clear(color: Int = 0x000000) { fillRect(0, 0, size.x, size.y, color) }

    public fun rect(bounds: Rect2i, color: Int) {
        if (bounds.isEmpty) return
        val left = bounds.left
        val top = bounds.top
        val right = bounds.right - 1
        val bottom = bounds.bottom - 1
        line(left, top, right, top, color)
        line(left, bottom, right, bottom, color)
        line(left, top, left, bottom, color)
        line(right, top, right, bottom, color)
    }

    public fun text(x: Int, y: Int, text: String, color: Int = 0xFFFFFF, scale: Int = 1) {
        display.textClipped(global(Vector2i(x, y)), text, color, scale, clip)
    }

    public fun text(position: Position2i, text: String, color: Int = 0xFFFFFF, scale: Int = 1) {
        text(position.x, position.y, text, color, scale)
    }

    /** Tight row-major RGB image; clipping precedes transfer, preserving the existing image limits. */
    public fun image(position: Position2i, size: Size2i, pixels: IntArray) {
        require(size.x in 1..1024 && size.y in 1..1024 && size.x.toLong() * size.y <= 65536)
        require(pixels.size == size.x * size.y)
        for (color in pixels) require(color in 0..0xFFFFFF)
        val source = Rect2i(global(position), size)
        val visible = source.intersection(clip)
        if (visible.isEmpty) return
        if (visible == source) { display.image(source.position, size, pixels); return }
        val clipped = IntArray(visible.size.x * visible.size.y) { index ->
            val x = visible.left - source.left + index % visible.size.x
            val y = visible.top - source.top + index / visible.size.x
            pixels[y * size.x + x]
        }
        display.image(visible.position, visible.size, clipped)
    }

    internal fun child(position: Position2i, size: Size2i): CanvasScope {
        val rectangle = Rect2i(global(position), size)
        return CanvasScope(display, rectangle, rectangle.intersection(clip))
    }

    private fun global(position: Position2i): Position2i =
        Vector2i(canvasCoordinate(bounds.left.toLong() + position.x), canvasCoordinate(bounds.top.toLong() + position.y))
}

/** Creates a bounded local drawing surface at an arbitrary screen position. */
public fun DisplayFrame.canvas(bounds: Rect2i): CanvasScope = CanvasScope(this, bounds, bounds)

private fun canvasCoordinate(value: Long): Int {
    if (!(value >= Int.MIN_VALUE.toLong() && value <= Int.MAX_VALUE.toLong())) throw IllegalArgumentException("Canvas coordinate overflows")
    return value.toInt()
}
