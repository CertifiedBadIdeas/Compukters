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
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameOperation
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

    @Test
    fun publishesDirtyRegionsAndFrontVersionsAfterSwap() {
        val buffer = ClientDisplayBuffer(displayId = 1, width = 4, height = 2)
        val red565 = byteArrayOf(0xF8.toByte(), 0x00)
        val green565 = byteArrayOf(0x07, 0xE0.toByte())
        val fullPayload = ByteArray(4 * 2 * 2)
        val fullFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 1,
                width = 4,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = true,
                tiles = listOf(DisplayTile(0, 0, 0, 0, 4, 2, fullPayload)),
            )

        assertTrue(buffer.apply(fullFrame))
        assertTrue(buffer.swapIfDirty())
        assertEquals(1L, buffer.frontVersion)
        assertEquals(listOf(ClientDisplayBuffer.Region(0, 0, 4, 2)), buffer.frontDirtyRegions())

        val partialFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 2,
                width = 4,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = listOf(DisplayTile(0, 0, 1, 1, 2, 1, red565 + green565)),
            )

        assertTrue(buffer.apply(partialFrame))
        assertTrue(buffer.swapIfDirty())
        assertEquals(2L, buffer.frontVersion)
        assertEquals(listOf(ClientDisplayBuffer.Region(1, 1, 2, 1)), buffer.frontDirtyRegions())

        val snapshot = buffer.copyFrontSnapshotSince(uploadedVersion = 1)
        assertEquals(2L, snapshot.version)
        assertEquals(listOf(ClientDisplayBuffer.Region(1, 1, 2, 1)), snapshot.regions)
        assertEquals(0xFFFF0000.toInt(), snapshot.pixels[1 + 1 * 4])
        assertEquals(0xFF00FF00.toInt(), snapshot.pixels[2 + 1 * 4])

        val copied = IntArray(2)
        buffer.copyFrontArgbRegion(ClientDisplayBuffer.Region(1, 1, 2, 1), copied)
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), copied.toList())
    }

    @Test
    fun acceptsServerCoalescedPartialFrameThatAdvancesSequence() {
        val buffer = ClientDisplayBuffer(displayId = 1, width = 2, height = 1)
        val red565 = byteArrayOf(0xF8.toByte(), 0x00)
        val green565 = byteArrayOf(0x07, 0xE0.toByte())
        val initialFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 1,
                width = 2,
                height = 1,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = true,
                tiles = listOf(DisplayTile(0, 0, 0, 0, 2, 1, red565 + red565)),
            )
        val coalescedFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 3,
                width = 2,
                height = 1,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = listOf(DisplayTile(0, 0, 1, 0, 1, 1, green565)),
            )

        assertTrue(buffer.apply(initialFrame))
        assertTrue(buffer.apply(coalescedFrame))
        buffer.swapIfDirty()

        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), buffer.frontArgb().toList())
    }

    @Test
    fun appliesDisplayOperationsBeforeTilePayloads() {
        val buffer = ClientDisplayBuffer(displayId = 1, width = 4, height = 2)
        val red565 = byteArrayOf(0xF8.toByte(), 0x00)
        val green565 = byteArrayOf(0x07, 0xE0.toByte())
        val fullFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 1,
                width = 4,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = true,
                tiles = listOf(DisplayTile(0, 0, 0, 0, 4, 2, ByteArray(4 * 2 * 2))),
            )
        val operationFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 2,
                width = 4,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = listOf(DisplayTile(0, 0, 1, 0, 1, 1, green565)),
                operations =
                    listOf(
                        DisplayFrameOperation.FillRect(x = 0, y = 0, width = 2, height = 1, rgb565 = 0xF800),
                        DisplayFrameOperation.CopyRect(srcX = 0, srcY = 0, width = 2, height = 1, dstX = 2, dstY = 1),
                    ),
            )

        assertTrue(buffer.apply(fullFrame))
        assertTrue(buffer.swapIfDirty())
        assertTrue(buffer.apply(operationFrame))
        assertTrue(buffer.swapIfDirty())

        assertEquals(
            listOf(
                0xFFFF0000.toInt(),
                0xFF00FF00.toInt(),
                0xFF000000.toInt(),
                0xFF000000.toInt(),
                0xFF000000.toInt(),
                0xFF000000.toInt(),
                0xFFFF0000.toInt(),
                0xFFFF0000.toInt(),
            ),
            buffer.frontArgb().toList(),
        )
        assertEquals(
            listOf(
                ClientDisplayBuffer.Region(0, 0, 2, 1),
                ClientDisplayBuffer.Region(2, 1, 2, 1),
                ClientDisplayBuffer.Region(1, 0, 1, 1),
            ),
            buffer.frontDirtyRegions(),
        )
    }

    @Test
    fun cacheReusesReceivedBufferForSameComputerDisplayGeometry() {
        val cache = ClientDisplayBufferCache()
        val firstBuffer = cache.getOrCreate(computerId = 42, displayId = 1, width = 2, height = 1)
        val frame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 1,
                width = 2,
                height = 1,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = true,
                tiles = listOf(DisplayTile(0, 0, 0, 0, 2, 1, byteArrayOf(0xF8.toByte(), 0x00, 0x07, 0xE0.toByte()))),
            )

        assertTrue(firstBuffer.apply(frame))
        firstBuffer.swapIfDirty()

        val reopenedBuffer = cache.getOrCreate(computerId = 42, displayId = 1, width = 2, height = 1)

        assertSame(firstBuffer, reopenedBuffer)
        assertTrue(reopenedBuffer.hasReceivedFrames)
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), reopenedBuffer.frontArgb().toList())
    }

    @Test
    fun cacheRemoveDropsStoredBufferForPowerOffReset() {
        val cache = ClientDisplayBufferCache()
        val firstBuffer = cache.getOrCreate(computerId = 42, displayId = 1, width = 2, height = 1)

        cache.remove(computerId = 42, displayId = 1, width = 2, height = 1)

        val nextBuffer = cache.getOrCreate(computerId = 42, displayId = 1, width = 2, height = 1)

        assertFalse(nextBuffer === firstBuffer)
        assertFalse(nextBuffer.hasReceivedFrames)
    }
}
