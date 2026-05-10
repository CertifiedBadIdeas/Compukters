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

package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayStateTest {
    @Test
    fun presentReturnsDirtyTilesAndIncrementsSequence() {
        val state = DisplayState(displayId = 7, width = 20, height = 10, pixelFormat = DisplayPixelFormat.RGB565)

        state.fillRect(x = 1, y = 2, width = 3, height = 4, rgb565 = 0xF800)
        val first = assertNotNull(state.present())

        assertEquals(7, first.displayId)
        assertEquals(1L, first.sequence)
        assertEquals(20, first.width)
        assertEquals(10, first.height)
        assertEquals(DisplayPixelFormat.RGB565, first.pixelFormat)
        assertFalse(first.fullRefresh)
        assertTrue(first.tiles.isNotEmpty())
        assertNull(state.present())
    }

    @Test
    fun profiledPresentReturnsFrameBuildMetrics() {
        val state = DisplayState(displayId = 8, width = 16, height = 16, pixelFormat = DisplayPixelFormat.RGB565)
        state.fillRect(x = 0, y = 0, width = 8, height = 8, rgb565 = 0x07E0)

        val result = assertNotNull(state.presentWithMetrics())

        assertEquals(1, result.frame.tiles.size)
        assertEquals(1, result.metrics.tileCount)
        assertEquals(
            result.frame.tiles
                .sumOf { it.payload.size }
                .toLong(),
            result.metrics.payloadBytes,
        )
        assertTrue(result.metrics.totalNanos >= 0)
        assertTrue(result.metrics.tileSerializationNanos >= 0)
    }

    @Test
    fun fullRefreshMarksWholeDisplay() {
        val state = DisplayState(displayId = 1, width = 17, height = 17, pixelFormat = DisplayPixelFormat.RGB565)

        val frame = assertNotNull(state.fullRefresh())

        assertTrue(frame.fullRefresh)
        assertEquals(4, frame.tiles.size)
        assertEquals(1L, frame.sequence)
    }

    @Test
    fun copyRectCopiesPixelsAndMarksDestinationDirty() {
        val state = DisplayState(displayId = 2, width = 8, height = 4, pixelFormat = DisplayPixelFormat.RGB565)
        state.fillRect(x = 0, y = 0, width = 8, height = 4, rgb565 = 0x0000)
        state.fillRect(x = 0, y = 0, width = 2, height = 2, rgb565 = 0xF800)
        state.present()

        state.copyRect(srcX = 0, srcY = 0, width = 2, height = 2, dstX = 3, dstY = 1)
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0xF800), "copyRect should copy red pixels into emitted tiles")
        assertFalse(frame.fullRefresh)
    }

    @Test
    fun blitMonoDrawsForegroundAndBackground() {
        val state = DisplayState(displayId = 3, width = 8, height = 4, pixelFormat = DisplayPixelFormat.RGB565)
        state.blitMono(x = 1, y = 1, width = 3, height = 2, mask = "101010", foreground = 0x07E0, background = 0x0000)
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0x07E0), "blitMono should write foreground pixels")
        assertTrue(payload.containsRgb565(0x0000), "blitMono should write background pixels")
    }

    @Test
    fun blitMonoSupportsTransparentBackground() {
        val state = DisplayState(displayId = 4, width = 8, height = 4, pixelFormat = DisplayPixelFormat.RGB565)
        state.fillRect(x = 0, y = 0, width = 8, height = 4, rgb565 = 0x001F)
        state.present()

        state.blitMono(x = 1, y = 1, width = 3, height = 2, mask = "100000", foreground = 0x07E0, background = -1)
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0x07E0), "foreground should be drawn")
        assertTrue(payload.containsRgb565(0x001F), "transparent zeros should preserve old pixels")
    }

    @Test
    fun blitMono5x7DrawsForegroundAndBackground() {
        val state = DisplayState(displayId = 5, width = 8, height = 9, pixelFormat = DisplayPixelFormat.RGB565)

        state.blitMono5x7(
            x = 1,
            y = 1,
            row0 = 0b01110,
            row1 = 0b10001,
            row2 = 0b10001,
            row3 = 0b11111,
            row4 = 0b10001,
            row5 = 0b10001,
            row6 = 0b10001,
            foreground = 0x07E0,
            background = 0x0000,
        )
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0x07E0), "numeric glyph blit should write foreground pixels")
        assertTrue(payload.containsRgb565(0x0000), "numeric glyph blit should write background pixels")
    }

    @Test
    fun blitMono5x7SupportsTransparentBackground() {
        val state = DisplayState(displayId = 6, width = 8, height = 9, pixelFormat = DisplayPixelFormat.RGB565)
        state.fillRect(x = 0, y = 0, width = 8, height = 9, rgb565 = 0x001F)
        state.present()

        state.blitMono5x7(
            x = 1,
            y = 1,
            row0 = 0b10000,
            row1 = 0,
            row2 = 0,
            row3 = 0,
            row4 = 0,
            row5 = 0,
            row6 = 0,
            foreground = 0x07E0,
            background = -1,
        )
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0x07E0), "foreground should be drawn")
        assertTrue(payload.containsRgb565(0x001F), "transparent zeros should preserve old pixels")
    }

    @Test
    fun blitMono5x7TextDrawsGlyphRun() {
        val state = DisplayState(displayId = 7, width = 18, height = 9, pixelFormat = DisplayPixelFormat.RGB565)

        state.blitMono5x7Text(x = 0, y = 1, text = "AB", foreground = 0x07E0, background = -1)
        val frame = assertNotNull(state.present())
        val payload = frame.tiles.flatMap { it.payload.toList() }.toByteArray()

        assertTrue(payload.containsRgb565(0x07E0), "text run should draw foreground pixels")
    }

    @Test
    fun mono5x7FontUsesAlignedLowercaseGlyphs() {
        val expected =
            mapOf(
                'a' to 0b00000000000111000001011111000101111L,
                'b' to 0b10000100001011011001100011000111110L,
                'c' to 0b00000000000111010001100001000101110L,
                'd' to 0b00001000010110110011100011000101111L,
                'e' to 0b00000000000111010001111111000001111L,
                'f' to 0b00110010001110001000010000100001000L,
                'g' to 0b00000011111000110001011110000101110L,
                'h' to 0b10000100001011011001100011000110001L,
                'i' to 0b00100000000010000100001000010000100L,
                'j' to 0b00001000000000100001000011000101110L,
                'k' to 0b10000100001001010100110001010010001L,
                'l' to 0b00100001000010000100001000010000100L,
                'm' to 0b00000000001101010101101011000110001L,
                'n' to 0b00000000001111010001100011000110001L,
                'o' to 0b00000000000111010001100011000101110L,
                'p' to 0b00000111101000110001111101000010000L,
                'q' to 0b00000011111000110001011110000100001L,
                'r' to 0b00000000001011011001100001000010000L,
                's' to 0b00000000000111110000011100000111110L,
                't' to 0b00100001000111000100001000010000010L,
                'u' to 0b00000000001000110001100011000101111L,
                'v' to 0b00000000001000110001100010101000100L,
                'w' to 0b00000000001000110001101011010101010L,
                'x' to 0b00000000001000101010001000101010001L,
                'y' to 0b00000100011000101111000010000101110L,
                'z' to 0b00000000001111100010001000100011111L,
            )

        assertFalse(
            Mono5x7Font.packedBits('a') == Mono5x7Font.packedBits('A'),
            "lowercase glyphs should not be aliases of uppercase glyphs",
        )
        expected.forEach { (ch, glyph) ->
            assertEquals(glyph, Mono5x7Font.packedBits(ch), "unexpected glyph for `$ch`")
        }
    }

    @Test
    fun mono5x7FontIncludesBoxDrawingGlyphs() {
        val expected =
            mapOf(
                '─' to 0b00000000000000011111000000000000000L,
                '│' to 0b00100001000010000100001000010000100L,
                '┌' to 0b00000000000011100100001000010000100L,
                '┐' to 0b00000000001110000100001000010000100L,
                '└' to 0b00100001000010000111000000000000000L,
                '┘' to 0b00100001000010011100000000000000000L,
                '┼' to 0b00100001000010011111001000010000100L,
            )

        expected.forEach { (ch, glyph) ->
            assertEquals(glyph, Mono5x7Font.packedBits(ch), "unexpected glyph for `$ch`")
        }
    }

    private fun ByteArray.containsRgb565(rgb565: Int): Boolean {
        var i = 0
        val hi = (rgb565 ushr 8).toByte()
        val lo = rgb565.toByte()
        while (i + 1 < size) {
            if (this[i] == hi && this[i + 1] == lo) return true
            i += 2
        }
        return false
    }
}
