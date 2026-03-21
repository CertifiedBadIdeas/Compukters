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
import kotlin.math.max
import kotlin.math.min

class TextBuffer {
    private val text: CharArray

    constructor(c: Char, length: Int) {
        text = CharArray(length)
        fill(c)
    }

    constructor(text: String) {
        this.text = text.toCharArray()
    }

    fun length(): Int = text.size

    @JvmOverloads
    fun write(
        text: String,
        start: Int = 0,
    ) {
        var start = start
        val pos = start
        start = max(start, 0)
        var end = min(start + text.length, pos + text.length)
        end = min(end, this.text.size)
        for (i in start..<end) {
            this.text[i] = text.get(i - pos)
        }
    }

    fun write(
        text: ByteBuffer,
        start: Int,
    ) {
        var start = start
        val pos = start
        val bufferPos = text.position()

        start = max(start, 0)
        val length = text.remaining()
        var end = min(start + length, pos + length)
        end = min(end, this.text.size)
        for (i in start..<end) {
            this.text[i] = (text.get(bufferPos + i - pos).toInt() and 0xFF).toChar()
        }
    }

    fun write(text: TextBuffer) {
        val end = min(text.length(), this.text.size)
        for (i in 0..<end) {
            this.text[i] = text.charAt(i)
        }
    }

    @JvmOverloads
    fun fill(
        c: Char,
        start: Int = 0,
        end: Int = text.size,
    ) {
        var start = start
        var end = end
        start = max(start, 0)
        end = min(end, text.size)
        for (i in start..<end) {
            text[i] = c
        }
    }

    fun charAt(i: Int): Char = text[i]

    fun setChar(
        i: Int,
        c: Char,
    ) {
        if (i >= 0 && i < text.size) {
            text[i] = c
        }
    }

    override fun toString(): String = String(text)
}
