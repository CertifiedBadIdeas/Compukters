/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import kotlin.math.abs
import kotlin.math.roundToInt

/** A published RGB canvas. Writer leases and unpublished frames are deliberately transient. */
class DisplayCanvas(
    val columns: Int,
    val rows: Int,
    mode: Int = 2,
    private val changed: () -> Unit = {},
    private val admitMode: (Int) -> Boolean = { true },
) {
    var mode: Int = mode
        private set
    val density: Int get() = DENSITIES[mode]
    val width: Int get() = columns * density
    val height: Int get() = rows * density
    var revision: Long = 0
        private set
    private var pixels: IntArray
    private var writer: Any? = null
    private var connected: () -> Boolean = { false }
    private var draft: IntArray? = null
    private var draftMode: Int = mode
    private var frameWork = 0L

    /** The world adapter charges deterministic pixel work before mutation. */
    var admitWork: (Long) -> Unit = {}

    init {
        require(columns in 1..MAXIMUM_BLOCK_SIDE && rows in 1..MAXIMUM_BLOCK_SIDE)
        require(mode in DENSITIES.indices)
        pixels = IntArray(width * height)
    }

    fun pixelAt(
        x: Int,
        y: Int,
    ): Int = pixels[y * width + x]

    fun owns(owner: Any): Boolean {
        expire()
        return writer === owner
    }

    fun acquire(
        owner: Any,
        valid: () -> Boolean,
    ) {
        expire()
        check(valid()) { "Display is disconnected" }
        check(writer == null || writer === owner) { "Display has another writer" }
        writer = owner
        connected = valid
    }

    fun release(owner: Any) {
        if (writer === owner) {
            writer = null
            connected = { false }
            draft = null
            frameWork = 0
        }
    }

    internal fun retire() {
        writer?.let(::release)
    }

    fun expire() {
        val owner = writer
        if (owner != null && !connected()) release(owner)
    }

    fun begin(owner: Any) {
        requireOwner(owner)
        check(draft == null) { "A display frame is already open" }
        check(pixels.size <= MAXIMUM_FRAME_PIXELS) { "Display exceeds coherent frame pixel limit" }
        charge(pixels.size.toLong())
        draft = pixels.copyOf()
        draftMode = mode
        frameWork = pixels.size.toLong()
    }

    fun finish(
        owner: Any,
        publish: Boolean,
    ) {
        requireOwner(owner)
        val pending = checkNotNull(draft) { "No display frame is open" }
        draft = null
        frameWork = 0
        if (publish) {
            check(admitMode(draftMode)) { "World display pixel budget exceeded" }
            pixels = pending
            mode = draftMode
            touch()
        }
    }

    fun setMode(
        owner: Any,
        requested: Int,
    ) {
        requireOwner(owner)
        require(requested in DENSITIES.indices) { "Unknown display mode" }
        if (requested == activeMode()) return
        check(admitMode(requested)) { "World display pixel budget exceeded" }
        val size = columns * rows * DENSITIES[requested] * DENSITIES[requested]
        charge(size.toLong())
        check(draft == null || size <= MAXIMUM_FRAME_PIXELS) { "Display exceeds coherent frame pixel limit" }
        val replacement = IntArray(size)
        if (draft != null) {
            draft = replacement
            draftMode = requested
        } else {
            mode = requested
            pixels = replacement
            touch()
        }
    }

    fun activeWidth(owner: Any): Int = columns * DENSITIES[if (writer === owner) activeMode() else mode]

    fun activeHeight(owner: Any): Int = rows * DENSITIES[if (writer === owner) activeMode() else mode]

    fun activeMode(owner: Any): Int = if (writer === owner) activeMode() else mode

    fun fill(
        owner: Any,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: Int,
    ) {
        requireOwner(owner)
        requireColor(color)
        require(w >= 0 && h >= 0) { "Negative rectangle size" }
        val aw = activeWidth(owner)
        val ah = activeHeight(owner)
        val left = x.toLong().coerceIn(0, aw.toLong()).toInt()
        val top = y.toLong().coerceIn(0, ah.toLong()).toInt()
        val right = (x.toLong() + w).coerceIn(0, aw.toLong()).toInt()
        val bottom = (y.toLong() + h).coerceIn(0, ah.toLong()).toInt()
        charge((right - left).toLong() * (bottom - top))
        val target = target()
        for (py in top until bottom) target.fill(color, py * aw + left, py * aw + right)
        if (left < right && top < bottom) edited()
    }

    fun pixel(
        owner: Any,
        x: Int,
        y: Int,
        color: Int,
    ) = fill(owner, x, y, 1, 1, color)

    /** Clips before rasterization, so extreme off-screen endpoints cannot cause unbounded work. */
    fun line(
        owner: Any,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int,
    ) {
        requireOwner(owner)
        requireColor(color)
        val aw = activeWidth(owner)
        val ah = activeHeight(owner)
        val dx = x1.toDouble() - x0
        val dy = y1.toDouble() - y0
        var start = 0.0
        var end = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(x0.toDouble(), aw - 1.0 - x0, y0.toDouble(), ah - 1.0 - y0)
        for (i in p.indices) {
            if (p[i] == 0.0) {
                if (q[i] < 0.0) return
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0) start = maxOf(start, t) else end = minOf(end, t)
                if (start > end) return
            }
        }
        var x = (x0 + start * dx).roundToInt().coerceIn(0, aw - 1)
        var y = (y0 + start * dy).roundToInt().coerceIn(0, ah - 1)
        val ex = (x0 + end * dx).roundToInt().coerceIn(0, aw - 1)
        val ey = (y0 + end * dy).roundToInt().coerceIn(0, ah - 1)
        val ax = abs(ex - x)
        val ay = -abs(ey - y)
        val sx = if (x < ex) 1 else -1
        val sy = if (y < ey) 1 else -1
        charge(maxOf(ax, -ay).toLong() + 1)
        var error = ax + ay
        val target = target()
        while (true) {
            target[y * aw + x] = color
            if (x == ex && y == ey) break
            val twice = error * 2
            if (twice >= ay) {
                error += ay
                x += sx
            }
            if (twice <= ax) {
                error += ax
                y += sy
            }
        }
        edited()
    }

    /** A bounded image row, used by text glyphs and Guest raster transfer. */
    fun imageRow(
        owner: Any,
        x: Int,
        y: Int,
        colors: IntArray,
        transparent: Boolean = false,
    ) {
        requireOwner(owner)
        require(colors.size <= MAXIMUM_IMAGE_ROW)
        require(colors.all { it in 0..0xFFFFFF || (transparent && it == -1) })
        charge(colors.size.toLong())
        val aw = activeWidth(owner)
        if (y !in 0 until activeHeight(owner)) return
        val target = target()
        colors.forEachIndexed { offset, color ->
            val px = x.toLong() + offset
            if (px in 0 until aw.toLong() && color != -1) target[y * aw + px.toInt()] = color
        }
        edited()
    }

    /** Charges the complete clipped area once, then applies an immutable monochrome mask. */
    fun mask(
        owner: Any,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: Int,
        visible: (Int, Int) -> Boolean,
    ) {
        requireOwner(owner)
        requireColor(color)
        require(w >= 0 && h >= 0)
        val aw = activeWidth(owner)
        val ah = activeHeight(owner)
        val left = x.toLong().coerceIn(0, aw.toLong()).toInt()
        val top = y.toLong().coerceIn(0, ah.toLong()).toInt()
        val right = (x.toLong() + w).coerceIn(0, aw.toLong()).toInt()
        val bottom = (y.toLong() + h).coerceIn(0, ah.toLong()).toInt()
        charge((right - left).toLong() * (bottom - top))
        val target = target()
        for (py in top until bottom) {
            for (px in left until right) {
                if (visible((px.toLong() - x).toInt(), (py.toLong() - y).toInt())) target[py * aw + px] = color
            }
        }
        if (left < right && top < bottom) edited()
    }

    fun tile(
        column: Int,
        row: Int,
    ): ByteArray {
        require(column in 0 until columns && row in 0 until rows)
        return ByteArray(density * density * 3).also { bytes ->
            for (y in 0 until density) {
                for (x in 0 until density) {
                    val color = pixelAt(column * density + x, row * density + y)
                    val at = (y * density + x) * 3
                    bytes[at] = (color shr 16).toByte()
                    bytes[at + 1] = (color shr 8).toByte()
                    bytes[at + 2] = color.toByte()
                }
            }
        }
    }

    fun frameWorkUsed(owner: Any): Long {
        requireOwner(owner)
        return frameWork
    }

    fun frameSnapshot(owner: Any): Pair<Int, ByteArray>? {
        requireOwner(owner)
        val pending = draft ?: return null
        return draftMode to encodePixels(pending)
    }

    fun restoreFrame(
        owner: Any,
        requestedMode: Int,
        bytes: ByteArray,
        work: Long = 0,
    ) {
        requireOwner(owner)
        require(requestedMode in DENSITIES.indices)
        val size = columns * rows * DENSITIES[requestedMode] * DENSITIES[requestedMode]
        require(size <= MAXIMUM_FRAME_PIXELS && bytes.size == size * 3)
        check(draft == null)
        require(work in 0..MAXIMUM_FRAME_WORK)
        draft = decodePixels(bytes)
        draftMode = requestedMode
        frameWork = work
    }

    fun encodeRgb(): ByteArray = encodePixels(pixels)

    private fun encodePixels(values: IntArray): ByteArray =
        ByteArray(values.size * 3).also { bytes ->
            values.forEachIndexed { index, color ->
                bytes[index * 3] = (color shr 16).toByte()
                bytes[index * 3 + 1] = (color shr 8).toByte()
                bytes[index * 3 + 2] = color.toByte()
            }
        }

    private fun decodePixels(bytes: ByteArray): IntArray =
        IntArray(bytes.size / 3) { index ->
            ((bytes[index * 3].toInt() and 255) shl 16) or
                ((bytes[index * 3 + 1].toInt() and 255) shl 8) or (bytes[index * 3 + 2].toInt() and 255)
        }

    /** Persistence import is all-or-nothing and never restores a writer or draft. */
    fun restoreRgb(
        requestedMode: Int,
        bytes: ByteArray,
    ) {
        require(requestedMode in DENSITIES.indices)
        val size = columns * rows * DENSITIES[requestedMode] * DENSITIES[requestedMode]
        require(bytes.size == size * 3) { "Invalid RGB payload length" }
        val restored = decodePixels(bytes)
        mode = requestedMode
        pixels = restored
        writer = null
        draft = null
        frameWork = 0
        touch()
    }

    private fun requireOwner(owner: Any) {
        expire()
        check(writer === owner) { "Display writer lease expired" }
    }

    private fun target(): IntArray = draft ?: pixels

    private fun activeMode(): Int = if (draft == null) mode else draftMode

    private fun edited() {
        if (draft == null) touch()
    }

    private fun touch() {
        revision++
        changed()
    }

    private fun charge(work: Long) {
        require(work in 0..MAXIMUM_DRAW_WORK) { "Display drawing limit exceeded" }
        admitWork(work)
        if (draft != null) {
            check(frameWork + work <= MAXIMUM_FRAME_WORK) { "Display frame work limit exceeded" }
            frameWork += work
        }
    }

    companion object {
        val DENSITIES: List<Int> = listOf(16, 32, 64, 128)
        const val MAXIMUM_BLOCK_SIDE: Int = 8
        const val MAXIMUM_IMAGE_ROW: Int = 1024
        const val MAXIMUM_DRAW_WORK: Long = 1024L * 1024
        const val MAXIMUM_FRAME_PIXELS: Int = 256 * 1024
        const val MAXIMUM_FRAME_WORK: Long = 4 * MAXIMUM_DRAW_WORK

        fun requireColor(color: Int) {
            require(color in 0..0xFFFFFF) { "Color must be 0xRRGGBB" }
        }
    }
}
