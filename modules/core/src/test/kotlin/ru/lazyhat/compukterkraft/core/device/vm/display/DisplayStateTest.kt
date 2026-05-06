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
