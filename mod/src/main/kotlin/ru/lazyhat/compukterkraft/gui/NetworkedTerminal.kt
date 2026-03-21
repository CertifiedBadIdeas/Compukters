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
package ru.lazyhat.compukterkraft.gui

import net.minecraft.nbt.CompoundTag

@OptIn(ExperimentalUnsignedTypes::class)
class NetworkedTerminal : Terminal {
    constructor(width: Int, height: Int, colour: Boolean) : super(width, height, colour)

    constructor(width: Int, height: Int, colour: Boolean, changedCallback: Runnable?) : super(width, height, colour, changedCallback)

    init {
        // LOGGER.info("NTerminal init: w: $width, h: $height, c: $isColour")
    }

    @Synchronized
    fun write(): TerminalState {
        val contents = UByteArray(width * height * 2 + Palette.PALETTE_SIZE * 3)
        var idx = 0

        for (y in 0..<height) {
            val text = this.text[y]
            val textColour = this.textColour[y]
            val backColour = backgroundColour[y]

            for (x in 0..<width) contents[idx++] = (text.charAt(x).code and 0xFF).toUByte()
            for (x in 0..<width) {
                contents[idx++] =
                    (
                        getColour(backColour.charAt(x), Colour.BLACK) shl 4 or
                            getColour(
                                textColour.charAt(x),
                                Colour.WHITE,
                            )
                    ).toUByte()
            }
        }

        for (i in 0..<Palette.PALETTE_SIZE) {
            for (channel in palette.getColour(i)) contents[idx++] = ((channel * 0xFF).toInt() and 0xFF).toUByte()
        }

        assert(idx == contents.size)
        return TerminalState(isColour, width, height, cursorX, cursorY, cursorBlink, cursorColour, cursorBackgroundColour, contents)
    }

    @Synchronized
    fun read(state: TerminalState) {
        resize(state.width, state.height)
        cursorX = state.cursorX
        cursorY = state.cursorY
        cursorBlink = state.cursorBlink

        cursorBackgroundColour = state.cursorBgColour
        this.cursorColour = state.cursorFgColour

        val contents = state.contents
        var idx = 0
        for (y in 0..<height) {
            val text = this.text[y]
            val textColour = this.textColour[y]
            val backColour = backgroundColour[y]

            for (x in 0..<width) text.setChar(x, contents[idx++].toInt().toChar())
            for (x in 0..<width) {
                val colour = contents[idx++]
                backColour.setChar(x, BASE_16[((colour / 16u) and 15u).toInt()])
                textColour.setChar(x, BASE_16[(colour and 15u).toInt()])
            }
        }

        for (i in 0..<Palette.PALETTE_SIZE) {
            val r = contents[idx++].toFloat() / 255f
            val g = contents[idx++].toFloat() / 255f
            val b = contents[idx++].toFloat() / 255f
            palette.setColour(i, r, g, b)
        }

        assert(idx == contents.size)
        setChanged()
    }

    @Synchronized
    fun writeToNBT(nbt: CompoundTag): CompoundTag {
        nbt.putInt("term_cursorX", cursorX)
        nbt.putInt("term_cursorY", cursorY)
        nbt.putBoolean("term_cursorBlink", cursorBlink)
        nbt.putInt("term_textColour", cursorColour)
        nbt.putInt("term_bgColour", cursorBackgroundColour)
        for (n in 0..<height) {
            nbt.putString("term_text_$n", text[n].toString())
            nbt.putString("term_textColour_$n", textColour[n].toString())
            nbt.putString("term_textBgColour_$n", backgroundColour[n].toString())
        }

        val rgb8 = IntArray(Palette.PALETTE_SIZE)
        for (i in 0..<Palette.PALETTE_SIZE) rgb8[i] = Palette.encodeRGB8(palette.getColour(i))
        nbt.putIntArray("term_palette", rgb8)

        return nbt
    }

    @Synchronized
    fun readFromNBT(nbt: CompoundTag) {
        cursorX = nbt.getInt("term_cursorX")
        cursorY = nbt.getInt("term_cursorY")
        cursorBlink = nbt.getBoolean("term_cursorBlink")
        cursorColour = nbt.getInt("term_textColour")
        cursorBackgroundColour = nbt.getInt("term_bgColour")

        for (n in 0..<height) {
            text[n].fill(' ')
            if (nbt.contains("term_text_$n")) {
                text[n].write(nbt.getString("term_text_$n"))
            }
            textColour[n].fill(BASE_16[cursorColour])
            if (nbt.contains("term_textColour_$n")) {
                textColour[n].write(nbt.getString("term_textColour_$n"))
            }
            backgroundColour[n].fill(BASE_16[cursorBackgroundColour])
            if (nbt.contains("term_textBgColour_$n")) {
                backgroundColour[n].write(nbt.getString("term_textBgColour_$n"))
            }
        }

        if (nbt.contains("term_palette")) {
            val rgb8: IntArray = nbt.getIntArray("term_palette")
            if (rgb8.size == Palette.PALETTE_SIZE) {
                for (i in 0..<Palette.PALETTE_SIZE) {
                    val colours = Palette.decodeRGB8(rgb8[i])
                    palette.setColour(i, colours[0], colours[1], colours[2])
                }
            }
        }
        setChanged()
    }
}
