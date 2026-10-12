/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import kotlin.math.floor

data class DisplayClick(
    val x: Int,
    val y: Int,
)

/** Facing follows DisplaySurface: north, south, west, east. Coordinates are local to a block. */
fun displayClickAt(
    facing: Int,
    column: Int,
    row: Int,
    density: Int,
    x: Double,
    y: Double,
    z: Double,
): DisplayClick? {
    if (facing !in 0..3 || column !in 0 until DisplayCanvas.MAXIMUM_BLOCK_SIDE || row !in 0 until DisplayCanvas.MAXIMUM_BLOCK_SIDE ||
        density !in DisplayCanvas.DENSITIES
    ) {
        return null
    }
    if (!x.isFinite() || !y.isFinite() || !z.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0 || z !in 0.0..1.0) return null
    val u =
        when (facing) {
            0 -> 1.0 - x
            1 -> x
            2 -> z
            else -> 1.0 - z
        }
    val px = floor(u * density).toInt().coerceIn(0, density - 1)
    val py = floor((1.0 - y) * density).toInt().coerceIn(0, density - 1)
    return DisplayClick(column * density + px, row * density + py)
}
