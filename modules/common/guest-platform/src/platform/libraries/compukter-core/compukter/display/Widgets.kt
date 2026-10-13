/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.display

import kotlin.math.Rect2i

/** Fixed-capacity chronological samples. NaN records a gap instead of a fabricated zero. */
public class DisplayHistory(
    public val capacity: Int,
) {
    private val values: FloatArray = historyValues(capacity)
    private var next: Int = 0
    private var count: Int = 0
    public val size: Int get() = count

    public fun add(value: Float) {
        values[next] = if (finite(value)) value else Float.NaN
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    public operator fun get(index: Int): Float {
        require(index in 0 until size)
        return values[(next - size + capacity + index) % capacity]
    }
}

/** Stateless widgets: the caller polls input and owns telemetry, commands and frame publication. */
public value class DisplayWidgets(
    private val display: CanvasScope,
) {
    public fun button(
        bounds: Rect2i,
        label: String,
        click: DisplayClick?,
    ): Boolean = button(bounds, label, click, true)

    public fun button(
        bounds: Rect2i,
        label: String,
        click: DisplayClick?,
        enabled: Boolean,
    ): Boolean {
        display.fillRect(bounds, if (enabled) 0x284C68 else 0x30363C)
        display.rect(bounds, if (enabled) 0x8EBBDC else 0x596168)
        display.text(bounds.left + 4, bounds.top + 4, label, if (enabled) Colors.WHITE else 0x7E858B)
        return enabled && click != null && click.position in bounds
    }

    public fun indicator(
        bounds: Rect2i,
        label: String,
        color: Int,
    ) {
        display.fillRect(bounds.left, bounds.top + 2, 8, 8, color)
        display.text(bounds.left + 12, bounds.top, label, color)
    }

    /** Non-finite readings leave an empty gauge. Range endpoints must be finite and ordered. */
    public fun gauge(
        bounds: Rect2i,
        value: Float,
        minimum: Float,
        maximum: Float,
        color: Int = 0x00FF00,
    ) {
        require(finite(minimum) && finite(maximum) && maximum > minimum)
        display.fillRect(bounds, 0x202B33)
        if (finite(value)) {
            val fraction = clampFraction((value.toDouble() - minimum) / (maximum.toDouble() - minimum))
            display.fillRect(bounds.left, bounds.top, (bounds.size.x * fraction).toInt(), bounds.size.y, color)
        }
        display.rect(bounds, 0x687D8C)
    }

    /** Draws oldest samples on the left and breaks the line at unavailable readings. */
    public fun chart(
        bounds: Rect2i,
        history: DisplayHistory,
        minimum: Float,
        maximum: Float,
        color: Int = 0x00FFFF,
    ) {
        require(finite(minimum) && finite(maximum) && maximum > minimum)
        display.fillRect(bounds, 0x101C25)
        if (bounds.size.x < 2 || bounds.size.y < 2) return
        var previousX = 0
        var previousY = 0
        var previousValid = false
        for (index in 0 until history.size) {
            val value = history[index]
            val x = bounds.left + (index.toLong() * (bounds.size.x - 1) / (history.capacity - 1)).toInt()
            if (finite(value)) {
                val fraction = clampFraction((value.toDouble() - minimum) / (maximum.toDouble() - minimum))
                val y = bounds.bottom - 1 - (fraction * (bounds.size.y - 1)).toInt()
                if (previousValid) display.line(previousX, previousY, x, y, color) else display.pixel(x, y, color)
                previousX = x
                previousY = y
                previousValid = true
            } else {
                previousValid = false
            }
        }
        display.rect(bounds, 0x687D8C)
    }
}

private fun finite(value: Float): Boolean = value >= -Float.MAX_VALUE && value <= Float.MAX_VALUE

private fun clampFraction(value: Double): Double =
    if (value < 0.0) {
        0.0
    } else if (value > 1.0) {
        1.0
    } else {
        value
    }

private fun historyValues(capacity: Int): FloatArray {
    require(capacity in 2..1024)
    return FloatArray(capacity)
}

/** Uses whole-screen coordinates; passing a CanvasScope directly gives local clipped widgets. */
public fun DisplayWidgets(display: DisplayFrame): DisplayWidgets =
    DisplayWidgets(display.canvas(Rect2i(kotlin.math.Vector2i(0, 0), kotlin.math.Vector2i(Int.MAX_VALUE, Int.MAX_VALUE))))
