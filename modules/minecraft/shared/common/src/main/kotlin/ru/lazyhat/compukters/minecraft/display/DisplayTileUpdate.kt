/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

/** Wire format 1: a full RGB tile (base -1), or a tight rectangle relative to an exact publication. */
internal data class DisplayTileUpdate(
    val base: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val rgb: ByteArray,
) {
    companion object {
        fun between(
            previous: ByteArray?,
            current: ByteArray,
            density: Int,
            base: Long,
        ): DisplayTileUpdate {
            if (previous == null || previous.size != current.size) return full(current, density)
            var left = density
            var top = density
            var right = -1
            var bottom = -1
            for (y in 0 until density) {
                for (x in 0 until density) {
                    val offset = (y * density + x) * 3
                    if (previous[offset] != current[offset] || previous[offset + 1] != current[offset + 1] ||
                        previous[offset + 2] != current[offset + 2]
                    ) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < 0) return DisplayTileUpdate(base, 0, 0, 0, 0, byteArrayOf())
            val width = right - left + 1
            val height = bottom - top + 1
            if (width * height == density * density) return full(current, density)
            val bytes = ByteArray(width * height * 3)
            for (row in 0 until height) {
                current.copyInto(
                    bytes,
                    row * width * 3,
                    ((top + row) * density + left) * 3,
                    ((top + row) * density + left + width) * 3,
                )
            }
            return DisplayTileUpdate(base, left, top, width, height, bytes)
        }

        fun full(
            rgb: ByteArray,
            density: Int,
        ): DisplayTileUpdate = DisplayTileUpdate(-1, 0, 0, density, density, rgb)
    }
}
