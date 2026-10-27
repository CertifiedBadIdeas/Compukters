/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

/** Integer basis shared by saved geometry, assembly, pointer input and world rendering. */
data class DisplayOffset(
    val x: Int,
    val y: Int,
    val z: Int,
) {
    operator fun plus(other: DisplayOffset) = DisplayOffset(x + other.x, y + other.y, z + other.z)

    operator fun times(value: Int) = DisplayOffset(x * value, y * value, z * value)

    fun dot(other: DisplayOffset): Int = x * other.x + y * other.y + z * other.z
}

object DisplayFace {
    private val rights =
        listOf(
            DisplayOffset(-1, 0, 0),
            DisplayOffset(1, 0, 0),
            DisplayOffset(0, 0, 1),
            DisplayOffset(0, 0, -1),
            DisplayOffset(1, 0, 0),
            DisplayOffset(1, 0, 0),
        )
    private val downs =
        listOf(
            DisplayOffset(0, -1, 0),
            DisplayOffset(0, -1, 0),
            DisplayOffset(0, -1, 0),
            DisplayOffset(0, -1, 0),
            DisplayOffset(0, 0, 1),
            DisplayOffset(0, 0, -1),
        )
    private val normals =
        listOf(
            DisplayOffset(0, 0, -1),
            DisplayOffset(0, 0, 1),
            DisplayOffset(-1, 0, 0),
            DisplayOffset(1, 0, 0),
            DisplayOffset(0, 1, 0),
            DisplayOffset(0, -1, 0),
        )

    fun right(face: Int): DisplayOffset = rights[face]

    fun down(face: Int): DisplayOffset = downs[face]

    fun normal(face: Int): DisplayOffset = normals[face]

    fun offset(
        face: Int,
        column: Int,
        row: Int,
    ): DisplayOffset = right(face) * column + down(face) * row

    fun position(
        surface: DisplaySurface,
        column: Int,
        row: Int,
    ): DisplayOffset = DisplayOffset(surface.originX, surface.originY, surface.originZ) + offset(surface.facing, column, row)
}
