/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.display

import kotlin.math.Position2i
import kotlin.math.Rect2i
import kotlin.math.Size2i

/** A task-confined drawing recorder, valid only during its enclosing frame block. */
public class DisplayFrame @PublishedApi internal constructor(private val display: GraphicalDisplay) {
    private val bytes = ByteArray(4096)
    private var offset = 8
    private var commands = 0
    private var open = true

    private fun command(operation: Int, size: Int) {
        if (!open) throw IllegalStateException("Display frame is closed")
        if (size > 4088) throw IllegalArgumentException("Display command exceeds packet limit")
        if (offset + size > bytes.size || commands == 128) flush()
        bytes[offset++] = operation.toByte()
        commands++
    }

    private fun int(value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
        offset += 4
    }

    private fun string(value: String) {
        int(value.length)
        for (index in 0 until value.length) {
            val code = value[index].code
            bytes[offset++] = code.toByte()
            bytes[offset++] = (code ushr 8).toByte()
        }
    }

    private fun clip(bounds: Rect2i) { int(bounds.left); int(bounds.top); int(bounds.size.x); int(bounds.size.y) }

    @PublishedApi
    internal fun flush() {
        if (!open) throw IllegalStateException("Display frame is closed")
        if (commands == 0) return
        val size = offset
        offset = 0
        int(1)
        int(commands)
        // A failed submission poisons the recorder, even if the caller catches the failure.
        open = false
        display.rasterBatch(bytes.copyOf(size))
        open = true
        offset = 8
        commands = 0
    }

    @PublishedApi
    internal fun close() { open = false }

    public fun clear(color: Int = 0x000000) { command(8, 5); int(color) }
    public fun pixel(x: Int, y: Int, color: Int) { command(9, 13); int(x); int(y); int(color) }
    public fun line(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        command(10, 21); int(x0); int(y0); int(x1); int(y1); int(color)
    }
    public fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int) {
        command(11, 21); int(x); int(y); int(width); int(height); int(color)
    }
    public fun text(x: Int, y: Int, text: String, color: Int = 0xFFFFFF, scale: Int = 1) {
        require(text.length <= 512)
        command(12, 21 + text.length * 2); int(x); int(y); string(text); int(color); int(scale)
    }
    internal fun lineClipped(start: Position2i, end: Position2i, color: Int, clip: Rect2i) {
        command(19, 37); int(start.x); int(start.y); int(end.x); int(end.y); int(color); clip(clip)
    }
    internal fun textClipped(position: Position2i, text: String, color: Int, scale: Int, clip: Rect2i) {
        require(text.length <= 512)
        command(20, 37 + text.length * 2); int(position.x); int(position.y); string(text); int(color); int(scale); clip(clip)
    }
    public fun rect(x: Int, y: Int, width: Int, height: Int, color: Int) {
        require(width >= 0 && height >= 0 && color in 0..0xFFFFFF)
        if (width == 0 || height == 0) return
        val right = x.toLong() + width - 1
        val bottom = y.toLong() + height - 1
        require(right in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && bottom in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        line(x, y, right.toInt(), y, color)
        line(x, bottom.toInt(), right.toInt(), bottom.toInt(), color)
        line(x, y, x, bottom.toInt(), color)
        line(right.toInt(), y, right.toInt(), bottom.toInt(), color)
    }
    public fun image(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
        require(width in 1..1024 && height in 1..1024)
        require(width.toLong() * height <= 65536 && pixels.size == width * height)
        for (color in pixels) require(color in 0..0xFFFFFF)
        for (row in 0 until height) {
            var column = 0
            while (column < width) {
                val count = if (width - column < 256) width - column else 256
                val px = x.toLong() + column
                val py = y.toLong() + row
                if (px in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && py in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    command(13, 13 + count * 4); int(px.toInt()); int(py.toInt()); int(count)
                    for (index in 0 until count) int(pixels[row * width + column + index])
                }
                column += count
            }
        }
    }
    public fun pixel(position: Position2i, color: Int) { pixel(position.x, position.y, color) }
    public fun line(start: Position2i, end: Position2i, color: Int) { line(start.x, start.y, end.x, end.y, color) }
    public fun fillRect(position: Position2i, size: Size2i, color: Int) { fillRect(position.x, position.y, size.x, size.y, color) }
    public fun fillRect(bounds: Rect2i, color: Int) { fillRect(bounds.position, bounds.size, color) }
    public fun rect(position: Position2i, size: Size2i, color: Int) { rect(position.x, position.y, size.x, size.y, color) }
    public fun rect(bounds: Rect2i, color: Int) { rect(bounds.position, bounds.size, color) }
    public fun text(position: Position2i, text: String, color: Int = 0xFFFFFF, scale: Int = 1) { text(position.x, position.y, text, color, scale) }
    public fun image(position: Position2i, size: Size2i, pixels: IntArray) { image(position.x, position.y, size.x, size.y, pixels) }
}
