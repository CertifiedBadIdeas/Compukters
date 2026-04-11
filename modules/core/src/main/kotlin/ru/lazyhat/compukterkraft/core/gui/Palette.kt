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

class Palette(
    private val colour: Boolean,
) {
    private val colours = Array(PALETTE_SIZE) { FloatArray(3) }
    private val byteColours = IntArray(PALETTE_SIZE)

    init {
        resetColours()
    }

    fun setColour(
        i: Int,
        r: Float,
        g: Float,
        b: Float,
    ) {
        if (i !in 0..<PALETTE_SIZE) return
        colours[i][0] = r
        colours[i][1] = g
        colours[i][2] = b

        if (colour) {
            byteColours[i] = packColour((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        } else {
            val grey = ((r + g + b) / 3 * 255).toInt()
            byteColours[i] = packColour(grey, grey, grey)
        }
    }

    fun setColour(
        i: Int,
        colour: Colour,
    ) {
        setColour(i, colour.r, colour.g, colour.b)
    }

    fun getColour(i: Int): FloatArray = colours[i]

    /**
     * Get the colour as a set of ARGB values suitable for rendering. Colours are automatically converted to greyscale
     * when using a black and white palette.
     *
     *
     * This returns a packed 32-bit ARGB colour.
     *
     * @param i The colour index.
     * @return The actual RGB colour.
     */
    fun getRenderColours(i: Int): Int = byteColours[i]

    fun resetColours() {
        for (i in 0..<Colour.entries.size) setColour(i, Colour.entries[i])
    }

    companion object {
        const val PALETTE_SIZE: Int = 16

        val DEFAULT: Palette = Palette(true)

        private fun packColour(
            r: Int,
            g: Int,
            b: Int,
        ): Int = 255 shl 24 or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)

        fun encodeRGB8(rgb: FloatArray): Int {
            val r = (rgb[0] * 255).toInt() and 0xFF
            val g = (rgb[1] * 255).toInt() and 0xFF
            val b = (rgb[2] * 255).toInt() and 0xFF

            return (r shl 16) or (g shl 8) or b
        }

        fun decodeRGB8(rgb: Int): FloatArray =
            floatArrayOf(
                (((rgb shr 16) and 0xFF) / 255.0f),
                (((rgb shr 8) and 0xFF) / 255.0f),
                ((rgb and 0xFF) / 255.0f),
            )
    }
}
