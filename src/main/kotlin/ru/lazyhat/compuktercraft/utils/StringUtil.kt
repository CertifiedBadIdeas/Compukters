// SPDX-FileCopyrightText: 2017 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.utils

import java.nio.ByteBuffer
import kotlin.math.min

object StringUtil {
    const val MAX_PASTE_LENGTH: Int = 512

    /**
     * Convert a Unicode character to a terminal one.
     *
     * @param chr The Unicode character.
     * @return The terminal character. This is either in the range [0, 255] (if a valid character) or `-1` if
     * it cannot be mapped to CC's charset.
     */
    fun unicodeToTerminal(chr: Int): Int {
        // ASCII and latin1 map to themselves
        if (chr == 0 || chr == '\t'.code || chr == '\n'.code || chr == '\r'.code || (chr >= ' '.code && chr <= '~'.code) ||
            (chr >= 160 && chr <= 255)
        ) {
            return chr
        }

        // Teletext block mosaics are *fairly* contiguous.
        if (chr >= 0x1FB00 && chr <= 0x1FB13) return chr + (129 - 0x1fb00)
        if (chr >= 0x1FB14 && chr <= 0x1FB1D) return chr + (150 - 0x1fb14)

        // Everything else is just a manual lookup. For now, we just use a big switch statement, which we spin into a
        // separate function to hopefully avoid inlining it here.
        return unicodeToCraftOsFallback(chr)
    }

    private fun unicodeToCraftOsFallback(c: Int): Int =
        when (c) {
            0x263A -> 1
            0x263B -> 2
            0x2665 -> 3
            0x2666 -> 4
            0x2663 -> 5
            0x2660 -> 6
            0x2022 -> 7
            0x25D8 -> 8
            0x2642 -> 11
            0x2640 -> 12
            0x266A -> 14
            0x266B -> 15
            0x25BA -> 16
            0x25C4 -> 17
            0x2195 -> 18
            0x203C -> 19
            0x25AC -> 22
            0x21A8 -> 23
            0x2191 -> 24
            0x2193 -> 25
            0x2192 -> 26
            0x2190 -> 27
            0x221F -> 28
            0x2194 -> 29
            0x25B2 -> 30
            0x25BC -> 31
            0x1FB99 -> 127
            0x258C -> 149
            else -> -1
        }

    /**
     * Check if a character is capable of being input and passed to a [ &quot;char&quot; event][ComputerEvents.charTyped].
     *
     * @param chr The character to check.
     * @return Whether this character can be typed.
     */
    fun isTypableChar(chr: Byte): Boolean = isTypableChar(chr.toInt() and 0xFF)

    /**
     * Check if a character is capable of being input and passed to a [ &quot;char&quot; event][ComputerEvents.charTyped].
     *
     * @param chr The character to check.
     * @return Whether this character can be typed.
     */
    fun isTypableChar(chr: Int): Boolean = chr in 0..255 && chr != 0 && chr != '\r'.code && chr != '\n'.code

    private fun isAllowedInLabel(c: Char): Boolean {
        // Limit to ASCII and latin1, excluding '§' (Minecraft's formatting character).
        return (c in ' '..'~') || (c.code in 161..255 && c.code != 167)
    }

    fun normaliseLabel(text: String): String {
        val length = min(32, text.length)
        val builder = StringBuilder(length)
        for (i in 0..<length) {
            val c = text.get(i)
            builder.append(if (isAllowedInLabel(c)) c else '?')
        }
        return builder.toString()
    }

    /**
     * Convert a Java string to a Lua one (using the terminal charset), suitable for pasting into a computer.
     *
     *
     * This removes special characters and strips to the first line of text.
     *
     * @param clipboard The text from the clipboard.
     * @return The encoded clipboard text.
     */
    fun getClipboardString(clipboard: String): ByteBuffer {
        val output = ByteArray(min(MAX_PASTE_LENGTH, clipboard.length))
        var idx = 0

        val iterator = clipboard.codePoints().iterator()
        while (iterator.hasNext() && idx < output.size) {
            val chr = unicodeToTerminal(iterator.next())
            if (chr < 0) continue // Strip out unconvertible characters

            if (!isTypableChar(chr)) break // Stop at untypable ones.

            output[idx++] = chr.toByte()
        }

        return ByteBuffer.wrap(output, 0, idx).asReadOnlyBuffer()
    }
}
