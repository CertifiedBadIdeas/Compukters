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

import java.nio.ByteBuffer

open class Terminal
    @JvmOverloads
    constructor(
        width: Int,
        height: Int,
        val isColour: Boolean,
        private val onChanged: Runnable? = null,
    ) {
        var width: Int = width
            protected set
        var height: Int = height
            protected set
        var cursorX: Int = 0
            protected set
        var cursorY: Int = 0
            protected set

        protected var cursorBlink: Boolean = true // false
        protected var cursorColour: Int = 0
        protected var cursorBackgroundColour: Int = 15

        protected var text: ArrayList<TextBuffer> = ArrayList(height)
        protected var textColour: ArrayList<TextBuffer> = ArrayList(height)
        protected var backgroundColour: ArrayList<TextBuffer> = ArrayList(height)

        val palette: Palette = Palette(isColour)

        init {
            repeat(height) { i ->
                text.add(TextBuffer(' ', this.width))
                textColour.add(TextBuffer(BASE_16[cursorColour], this.width))
                backgroundColour.add(TextBuffer(BASE_16[cursorBackgroundColour], this.width))
            }
        }

        @Synchronized
        fun reset() {
            cursorColour = 0
            cursorBackgroundColour = 15
            cursorX = 0
            cursorY = 0
            cursorBlink = false
            clear()
            setChanged()
            palette.resetColours()
        }

        @Synchronized
        fun resize(
            width: Int,
            height: Int,
        ) {
            if (width == this.width && height == this.height) {
                return
            }

            val oldHeight = this.height
            val oldWidth = this.width
            val oldText = text
            val oldTextColour: ArrayList<TextBuffer> = textColour
            val oldBackgroundColour: ArrayList<TextBuffer> = backgroundColour

            this.width = width
            this.height = height

            text = ArrayList(height)
            textColour = ArrayList(height)
            backgroundColour = ArrayList(height)
            repeat(height) { i ->
                if (i >= oldHeight) {
                    text[i] = TextBuffer(' ', this.width)
                    textColour[i] = TextBuffer(BASE_16[cursorColour], this.width)
                    backgroundColour[i] = TextBuffer(BASE_16[cursorBackgroundColour], this.width)
                } else if (this.width == oldWidth) {
                    text[i] = oldText[i]
                    textColour[i] = oldTextColour[i]
                    backgroundColour[i] = oldBackgroundColour[i]
                } else {
                    text[i] = TextBuffer(' ', this.width)
                    textColour[i] = TextBuffer(BASE_16[cursorColour], this.width)
                    backgroundColour[i] = TextBuffer(BASE_16[cursorBackgroundColour], this.width)
                    text[i].write(oldText[i])
                    textColour[i].write(oldTextColour[i])
                    backgroundColour[i].write(oldBackgroundColour[i])
                }
            }
            setChanged()
        }

        fun setCursorPos(
            x: Int,
            y: Int,
        ) {
            if (cursorX != x || cursorY != y) {
                cursorX = x
                cursorY = y
                setChanged()
            }
        }

        @JvmName("setCursorBlinkPublic")
        fun setCursorBlink(blink: Boolean) {
            if (cursorBlink != blink) {
                cursorBlink = blink
                setChanged()
            }
        }

        fun setTextColour(colour: Int) {
            if (cursorColour != colour) {
                cursorColour = colour
                setChanged()
            }
        }

        fun setBackgroundColour(colour: Int) {
            if (cursorBackgroundColour != colour) {
                cursorBackgroundColour = colour
                setChanged()
            }
        }

        @JvmName("getCursorBlinkPublic")
        fun getCursorBlink(): Boolean = cursorBlink

        fun getTextColour(): Int = cursorColour

        fun getBackgroundColour(): Int = cursorBackgroundColour

        @Synchronized
        fun blit(
            text: ByteBuffer,
            textColour: ByteBuffer,
            backgroundColour: ByteBuffer,
        ) {
            val x = cursorX
            val y = cursorY
            if (y in 0..<height) {
                this.text[y].write(text, x)
                this.textColour[y].write(textColour, x)
                this.backgroundColour[y].write(backgroundColour, x)
                setChanged()
            }
        }

        @Synchronized
        fun write(text: String) {
            val x = cursorX
            val y = cursorY
            if (y in 0..<height) {
                this.text[y].write(text, x)
                textColour[y].fill(BASE_16.get(cursorColour), x, x + text.length)
                backgroundColour[y].fill(BASE_16.get(cursorBackgroundColour), x, x + text.length)
                setChanged()
            }
        }

        @Synchronized
        fun scroll(yDiff: Int) {
            if (yDiff != 0) {
                val newText = ArrayList<TextBuffer>(height)
                val newTextColour = ArrayList<TextBuffer>(height)
                val newBackgroundColour = ArrayList<TextBuffer>(height)
                repeat(height) { y ->
                    val oldY = y + yDiff
                    if (oldY in 0..<height) {
                        newText[y] = text[oldY]
                        newTextColour[y] = textColour[oldY]
                        newBackgroundColour[y] = backgroundColour[oldY]
                    } else {
                        newText[y] = TextBuffer(' ', width)
                        newTextColour[y] = TextBuffer(BASE_16[cursorColour], width)
                        newBackgroundColour[y] = TextBuffer(BASE_16[cursorBackgroundColour], width)
                    }
                }
                text = newText
                textColour = newTextColour
                backgroundColour = newBackgroundColour
                setChanged()
            }
        }

        @Synchronized
        fun clear() {
            repeat(height) { y ->
                text[y].fill(' ')
                textColour[y].fill(BASE_16[cursorColour])
                backgroundColour[y].fill(BASE_16[cursorBackgroundColour])
            }
            setChanged()
        }

        @Synchronized
        fun clearLine() {
            val y = cursorY
            if (y in 0..<height) {
                text[y].fill(' ')
                textColour[y].fill(BASE_16[cursorColour])
                backgroundColour[y].fill(BASE_16[cursorBackgroundColour])
                setChanged()
            }
        }

        @Synchronized
        fun getLine(y: Int): TextBuffer = text[y]

        @Synchronized
        fun setLine(
            y: Int,
            text: String,
            textColour: String,
            backgroundColour: String,
        ) {
            this.text[y].write(text)
            this.textColour[y].write(textColour)
            this.backgroundColour[y].write(backgroundColour)
            setChanged()
        }

        @Synchronized
        fun getTextColourLine(y: Int): TextBuffer = textColour[y]

        @Synchronized
        fun getBackgroundColourLine(y: Int): TextBuffer = backgroundColour[y]

        fun setChanged() {
            onChanged?.run()
        }

        companion object {
            const val BASE_16: String = "0123456789abcdef"

            fun getColour(
                c: Char,
                def: Colour,
            ): Int {
                if (c in '0'..'9') return c.code - '0'.code
                if (c in 'a'..'f') return c.code - 'a'.code + 10
                if (c in 'A'..'F') return c.code - 'A'.code + 10
                return 15 - def.ordinal
            }
        }
    }
