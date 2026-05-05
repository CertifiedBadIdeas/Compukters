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

package ru.lazyhat.compukterkraft.common.computer.client

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientDisplayBufferTest {
    @Test
    fun appliesRgb565TileToStagingAndSwapsToFront() {
        val buffer = ClientDisplayBuffer(displayId = 1, width = 2, height = 1)
        val red565 = byteArrayOf(0xF8.toByte(), 0x00)
        val green565 = byteArrayOf(0x07, 0xE0.toByte())
        val frame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 1,
                width = 2,
                height = 1,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = true,
                tiles = listOf(DisplayTile(0, 0, 0, 0, 2, 1, red565 + green565)),
            )

        assertFalse(buffer.hasReceivedFrames)
        assertTrue(buffer.apply(frame))
        assertTrue(buffer.hasReceivedFrames)
        buffer.swapIfDirty()

        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), buffer.frontArgb().toList())
    }
}
