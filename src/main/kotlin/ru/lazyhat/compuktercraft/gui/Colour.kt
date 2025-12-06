// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL
package ru.lazyhat.compuktercraft.gui

enum class Colour(
    hex: Int,
) {
    BLACK(0x111111),
    RED(0xcc4c4c),
    GREEN(0x57A64E),
    BROWN(0x7f664c),
    BLUE(0x3366cc),
    PURPLE(0xb266e5),
    CYAN(0x4c99b2),
    LIGHT_GREY(0x999999),
    GREY(0x4c4c4c),
    PINK(0xf2b2cc),
    LIME(0x7fcc19),
    YELLOW(0xdede6c),
    LIGHT_BLUE(0x99b2f2),
    MAGENTA(0xe57fd8),
    ORANGE(0xf2b233),
    WHITE(0xf0f0f0),
    ;

    val r: Float = ((hex shr 16) and 0xFF) / 255.0f
    val g: Float = ((hex shr 8) and 0xFF) / 255.0f
    val b: Float = (hex and 0xFF) / 255.0f

    val next: Colour
        get() = entries[(ordinal + 1) % 16]

    val previous: Colour
        get() = entries[(ordinal + 15) % 16]

    companion object {
        fun fromInt(colour: Int): Colour = entries[colour]
    }
}
