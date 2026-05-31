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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.core.gui.GeneratedTerminalFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SerialTextDisplayRendererTest {
    @Test
    fun rendersSerialBytesIntoTerminalRows() {
        val renderer = SerialTextDisplayRenderer(columns = 6, rows = 3)

        renderer.append("K16\nOS".encodeToByteArray())
        val frame = renderer.renderFrame(displayId = 1, pixelWidth = 36, pixelHeight = 27)

        assertEquals("K16   ", renderer.rowText(0))
        assertEquals("OS    ", renderer.rowText(1))
        assertEquals(1, frame.displayId)
        assertEquals(1, frame.sequence)
        assertEquals(36, frame.width)
        assertEquals(27, frame.height)
        assertEquals(DisplayPixelFormat.RGB565, frame.pixelFormat)
        assertTrue(frame.fullRefresh)
        assertEquals(1, frame.tiles.size)
        assertEquals(36 * 27 * 2, frame.tiles.single().payload.size)
        assertNotEquals(0, frame.tiles.single().payload.count { it != 0.toByte() })
    }

    @Test
    fun scrollsWhenOutputExceedsVisibleRows() {
        val renderer = SerialTextDisplayRenderer(columns = 4, rows = 2)

        renderer.append("one\ntwo\nthree".encodeToByteArray())

        assertEquals("two ", renderer.rowText(0))
        assertEquals("thre", renderer.rowText(1))
    }

    @Test
    fun carriageReturnOverwritesCurrentRow() {
        val renderer = SerialTextDisplayRenderer(columns = 5, rows = 1)

        renderer.append("abc\rX".encodeToByteArray())

        assertEquals("Xbc  ", renderer.rowText(0))
    }

    @Test
    fun generatedFontCoversPrintableAsciiAndTerminalBoxGlyphs() {
        for (code in 0x20..0x7e) {
            val ch = code.toChar()
            assertTrue(GeneratedTerminalFont.hasGlyph(ch), "missing glyph for printable ASCII `$ch`")
        }

        for (ch in listOf('─', '│', '┌', '┐', '└', '┘', '┼')) {
            assertTrue(GeneratedTerminalFont.hasGlyph(ch), "missing box drawing glyph `$ch`")
        }
    }

    @Test
    fun generatedFontKeepsLowercaseDistinctFromUppercase() {
        for ((lower, upper) in listOf('a' to 'A', 'e' to 'E', 'o' to 'O', 'x' to 'X')) {
            assertNotEquals(
                GeneratedTerminalFont.glyph(lower),
                GeneratedTerminalFont.glyph(upper),
                "glyph `$lower` should not collapse to `$upper`",
            )
        }
    }

    @Test
    fun generatedFontUsesExplicitFallbackForUnknownGlyphs() {
        assertEquals(GeneratedTerminalFont.glyph('\u2603'), GeneratedTerminalFont.glyph('\ufffd'))
    }
}
