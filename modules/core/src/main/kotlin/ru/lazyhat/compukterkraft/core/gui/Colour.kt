/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.core.gui

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
