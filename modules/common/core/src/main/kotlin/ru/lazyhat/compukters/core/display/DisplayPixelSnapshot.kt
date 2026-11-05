/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

/** Immutable publication input. Its private pixel storage is never shared with a live canvas. */
class DisplayPixelSnapshot internal constructor(
    val columns: Int,
    val rows: Int,
    val mode: Int,
    val revision: Long,
    private val pixels: IntArray,
) {
    fun tile(
        column: Int,
        row: Int,
    ): ByteArray {
        require(column in 0 until columns && row in 0 until rows)
        val density = DisplayCanvas.DENSITIES[mode]
        val width = columns * density
        return ByteArray(density * density * 3).also { rgb ->
            for (y in 0 until density) {
                for (x in 0 until density) {
                    val color = pixels[(row * density + y) * width + column * density + x]
                    val at = (y * density + x) * 3
                    rgb[at] = (color shr 16).toByte()
                    rgb[at + 1] = (color shr 8).toByte()
                    rgb[at + 2] = color.toByte()
                }
            }
        }
    }
}
