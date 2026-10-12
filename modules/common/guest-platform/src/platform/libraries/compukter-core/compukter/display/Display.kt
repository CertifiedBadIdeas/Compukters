/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package compukter.display

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

import compukter.peripheral.Peripheral
import compukter.peripheral.TypedPeripheralProvider
import kotlin.math.Position2i
import kotlin.math.Rect2i
import kotlin.math.Size2i
import kotlin.math.Vector2i

/** A handle to one persistent canvas through the legacy text grid API. Coordinates are zero-based. */
public value class TextDisplay internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<TextDisplay>("compukter:text_display") {
        override fun wrap(handle: Int): TextDisplay = TextDisplay(handle)
    }

    /** Writes within one row of the screen's grid: 20 columns per block across and 10 rows per block down. */
    public fun writeAt(x: Int, y: Int, text: String) {
        DisplayBindings.writeAt(handle, x, y, text)
    }

    /** Writes at a grid position, rather than pixel coordinates. */
    public fun writeAt(position: Position2i, text: String) { writeAt(position.x, position.y, text) }

    public fun clear() {
        DisplayBindings.clear(handle)
    }
}

private object DisplayBindings {
    external fun acquireSide(side: Int): Int

    external fun acquireNamed(name: String): Int

    external fun writeAt(handle: Int, x: Int, y: Int, text: String)

    external fun clear(handle: Int)
    external fun width(handle: Int): Int
    external fun height(handle: Int): Int
    external fun mode(handle: Int): Int
    external fun setMode(handle: Int, mode: Int)
    external fun fill(handle: Int, color: Int)
    external fun pixel(handle: Int, x: Int, y: Int, color: Int)
    external fun line(handle: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int)
    external fun fillRect(handle: Int, x: Int, y: Int, width: Int, height: Int, color: Int)
    external fun text(handle: Int, x: Int, y: Int, text: String, color: Int, scale: Int)
    external fun imageRow(handle: Int, x: Int, y: Int, encoded: String)
    external fun beginFrame(handle: Int)
    external fun commitFrame(handle: Int)
    external fun pollClick(handle: Int): ByteArray
    external fun pollPointer(handle: Int): ByteArray
    external fun lineClipped(handle: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, clipX: Int, clipY: Int, clipWidth: Int, clipHeight: Int)
    external fun textClipped(handle: Int, x: Int, y: Int, text: String, color: Int, scale: Int, clipX: Int, clipY: Int, clipWidth: Int, clipHeight: Int)
    external fun abortFrame(handle: Int)
}

/** A front-face empty-hand click in published whole-screen pixel coordinates. */
public value class DisplayClick(
    val position: Position2i,
)

/** Pointer lifecycle; Cancel with gesture zero cancels every captured gesture. */
public enum class DisplayPointerKind { DOWN, DRAG, UP, CANCEL }

public value class DisplayPointerEvent internal constructor(private val tag: Int, val gesture: Int, val position: Position2i) {
    public val kind: DisplayPointerKind
        get() = when (tag) {
            0 -> DisplayPointerKind.DOWN
            1 -> DisplayPointerKind.DRAG
            2 -> DisplayPointerKind.UP
            else -> DisplayPointerKind.CANCEL
        }
}

public fun DisplayPointerEvent(kind: DisplayPointerKind, gesture: Int, position: Position2i): DisplayPointerEvent =
    DisplayPointerEvent(when (kind) {
        DisplayPointerKind.DOWN -> 0
        DisplayPointerKind.DRAG -> 1
        DisplayPointerKind.UP -> 2
        DisplayPointerKind.CANCEL -> 3
    }, gesture, position)

/** Pixels per installed block; the same density also applies to holes in the canvas. */
public enum class DisplayMode {
    ULTRA_LOW,
    LOW,
    NORMAL,
    HIGH,
}

/** Returns a 24-bit 0xRRGGBB color. */
public fun rgb(red: Int, green: Int, blue: Int): Int {
    require(red in 0..255 && green in 0..255 && blue in 0..255)
    return (red shl 16) or (green shl 8) or blue
}

public object Colors {
    public const val BLACK: Int = 0x000000
    public const val WHITE: Int = 0xFFFFFF
    public const val RED: Int = 0xFF0000
    public const val GREEN: Int = 0x00FF00
    public const val BLUE: Int = 0x0000FF
    public const val YELLOW: Int = 0xFFFF00
    public const val CYAN: Int = 0x00FFFF
    public const val MAGENTA: Int = 0xFF00FF
}

/** One persistent RGB canvas. Drawing is clipped to its bounds and publishes immediately by default. */
public value class GraphicalDisplay internal constructor(private val handle: Int) : Peripheral {
    public companion object : TypedPeripheralProvider<GraphicalDisplay>("compukter:graphical_display") {
        override fun wrap(handle: Int): GraphicalDisplay = GraphicalDisplay(handle)
    }

    public val width: Int get() = DisplayBindings.width(handle)
    public val height: Int get() = DisplayBindings.height(handle)
    public val size: Size2i get() = Vector2i(width, height)
    public val mode: DisplayMode
        get() = when (DisplayBindings.mode(handle)) {
            0 -> DisplayMode.ULTRA_LOW
            1 -> DisplayMode.LOW
            2 -> DisplayMode.NORMAL
            else -> DisplayMode.HIGH
        }

    /** Changing density clears the image. Selecting the current mode preserves it. */
    public fun setMode(mode: DisplayMode) {
        val value = when (mode) {
            DisplayMode.ULTRA_LOW -> 0
            DisplayMode.LOW -> 1
            DisplayMode.NORMAL -> 2
            DisplayMode.HIGH -> 3
        }
        DisplayBindings.setMode(handle, value)
    }

    /** Takes the oldest click, or null. Claims this program's exclusive display lease. */
    public fun pollClick(): DisplayClick? {
        val bytes = DisplayBindings.pollClick(handle)
        if (bytes.size == 0) return null
        check(bytes.size == 8)
        val x =
            (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8) or
                ((bytes[2].toInt() and 255) shl 16) or ((bytes[3].toInt() and 255) shl 24)
        val y =
            (bytes[4].toInt() and 255) or ((bytes[5].toInt() and 255) shl 8) or
                ((bytes[6].toInt() and 255) shl 16) or ((bytes[7].toInt() and 255) shl 24)
        return DisplayClick(Vector2i(x, y))
    }

    /** Polls transient held-right-click input in whole-screen coordinates, independently of pollClick. */
    public fun pollPointer(): DisplayPointerEvent? {
        val bytes = DisplayBindings.pollPointer(handle)
        if (bytes.size == 0) return null
        check(bytes.size == 16)
        val code = pointerInt(bytes, 0)
        val kind = when (code) {
            0 -> DisplayPointerKind.DOWN
            1 -> DisplayPointerKind.DRAG
            2 -> DisplayPointerKind.UP
            else -> { check(code == 3); DisplayPointerKind.CANCEL }
        }
        return DisplayPointerEvent(kind, pointerInt(bytes, 4), Vector2i(pointerInt(bytes, 8), pointerInt(bytes, 12)))
    }

    internal fun lineClipped(start: Position2i, end: Position2i, color: Int, clip: Rect2i) {
        DisplayBindings.lineClipped(handle, start.x, start.y, end.x, end.y, color, clip.left, clip.top, clip.size.x, clip.size.y)
    }

    internal fun textClipped(position: Position2i, text: String, color: Int, scale: Int, clip: Rect2i) {
        DisplayBindings.textClipped(handle, position.x, position.y, text, color, scale, clip.left, clip.top, clip.size.x, clip.size.y)
    }

    public fun clear(color: Int = 0x000000) { DisplayBindings.fill(handle, color) }
    public fun pixel(x: Int, y: Int, color: Int) { DisplayBindings.pixel(handle, x, y, color) }
    public fun line(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) { DisplayBindings.line(handle, x0, y0, x1, y1, color) }
    public fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int) {
        DisplayBindings.fillRect(handle, x, y, width, height, color)
    }

    public fun rect(x: Int, y: Int, width: Int, height: Int, color: Int) {
        require(width >= 0 && height >= 0)
        require(color in 0..0xFFFFFF)
        if (width == 0 || height == 0) return
        val right = x.toLong() + width - 1
        val bottom = y.toLong() + height - 1
        require(right in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && bottom in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        line(x, y, right.toInt(), y, color)
        line(x, bottom.toInt(), right.toInt(), bottom.toInt(), color)
        line(x, y, x, bottom.toInt(), color)
        line(right.toInt(), y, right.toInt(), bottom.toInt(), color)
    }

    /** Monospaced 6x12 glyphs, scaled by 1..8; newline starts the next line. */
    public fun text(x: Int, y: Int, text: String, color: Int = 0xFFFFFF, scale: Int = 1) {
        DisplayBindings.text(handle, x, y, text, color, scale)
    }

    /** Draws tightly packed row-major RGB pixels. At most 65536 pixels per image. */
    public fun image(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
        require(width in 1..1024 && height in 1..1024)
        require(width.toLong() * height <= 65536 && pixels.size == width * height)
        for (color in pixels) require(color in 0..0xFFFFFF)
        val digits = "0123456789abcdef"
        for (row in 0 until height) {
            var offset = 0
            while (offset < width) {
                val count = if (width - offset < 256) width - offset else 256
                val encoded = CharArray(count * 6) { index ->
                    val color = pixels[row * width + offset + index / 6]
                    digits[(color shr ((5 - index % 6) * 4)) and 15]
                }
                val px = x.toLong() + offset
                val py = y.toLong() + row
                if (px in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && py in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    DisplayBindings.imageRow(handle, px.toInt(), py.toInt(), String(encoded, 0, encoded.size))
                }
                offset += count
            }
        }
    }

    /** Typed pixel coordinates; floating vectors require an explicit rounding conversion. */
    public fun pixel(position: Position2i, color: Int) { pixel(position.x, position.y, color) }
    public fun line(start: Position2i, end: Position2i, color: Int) { line(start.x, start.y, end.x, end.y, color) }
    public fun fillRect(position: Position2i, size: Size2i, color: Int) { fillRect(position.x, position.y, size.x, size.y, color) }
    public fun fillRect(bounds: Rect2i, color: Int) { fillRect(bounds.position, bounds.size, color) }
    public fun rect(position: Position2i, size: Size2i, color: Int) { rect(position.x, position.y, size.x, size.y, color) }
    public fun rect(bounds: Rect2i, color: Int) { rect(bounds.position, bounds.size, color) }
    public fun text(position: Position2i, text: String, color: Int = 0xFFFFFF, scale: Int = 1) { text(position.x, position.y, text, color, scale) }
    public fun image(position: Position2i, size: Size2i, pixels: IntArray) { image(position.x, position.y, size.x, size.y, pixels) }

    @PublishedApi
    internal fun beginFrame() { DisplayBindings.beginFrame(handle) }

    @PublishedApi
    internal fun commitFrame() { DisplayBindings.commitFrame(handle) }

    @PublishedApi
    internal fun abortFrame() { DisplayBindings.abortFrame(handle) }
}

/** Publishes all enclosed changes together; exceptions discard the pending frame. Nested frames fail. */
public inline fun GraphicalDisplay.frame(block: GraphicalDisplay.() -> Unit) {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    beginFrame()
    var completed = false
    try {
        block()
        commitFrame()
        completed = true
    } finally {
        if (!completed) abortFrame()
    }
}

private fun pointerInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
        ((bytes[offset + 2].toInt() and 255) shl 16) or ((bytes[offset + 3].toInt() and 255) shl 24)
