/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.core.display.DisplayCanvas
import java.util.UUID

/** Independent block identity prevents late packets from targeting a replacement panel. */
data class WorldDisplayTile(
    val position: Long,
    val instance: UUID,
    val surface: UUID,
    val publication: Long,
    val mode: Int,
    val opacity: Int,
    val members: List<Long>,
    val base: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val rgb: ByteArray,
) {
    init {
        require(mode in DisplayCanvas.DENSITIES.indices && opacity in 0..255 && publication >= 0)
        require(members.size in 1..64 && members.distinct().size == members.size && position in members)
        val density = DisplayCanvas.DENSITIES[mode]
        require(x in 0..density && y in 0..density && width in 0..density && height in 0..density)
        require(x + width <= density && y + height <= density && rgb.size == width * height * 3)
        require(base >= -1 && (base == -1L || publication > base))
        require(
            if (base == -1L) {
                x == 0 && y == 0 && width == density && height == density
            } else {
                (width > 0 && height > 0) || (x == 0 && y == 0 && width == 0 && height == 0)
            },
        )
    }
}
