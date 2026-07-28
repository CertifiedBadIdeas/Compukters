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
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

        val scratch = IntArray(8)
        val changes = buffer.copyFrontChangesSince(uploadedVersion = 1, destination = scratch)
        assertEquals(2L, changes.version)
        assertEquals(
            listOf(
                ClientDisplayBuffer.PackedRegion(
                    region = ClientDisplayBuffer.Region(1, 1, 2, 1),
                    scratchOffset = 0,
                ),
            ),
            changes.regions,
        )
        assertEquals(2, changes.copiedPixels)
        assertEquals(0xFFFF0000.toInt(), scratch[0])
        assertEquals(0xFF00FF00.toInt(), scratch[1])

        val missed = buffer.copyFrontChangesSince(uploadedVersion = 0, destination = scratch)
        assertEquals(listOf(ClientDisplayBuffer.PackedRegion(ClientDisplayBuffer.Region(0, 0, 4, 2), 0)), missed.regions)
        assertEquals(8, missed.copiedPixels)

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
    fun appliesNativeFrameBatchWithoutMaterializingDisplayFrames() {
        val buffer = ClientDisplayBuffer(displayId = 1, width = 4, height = 2)
        val red565 = byteArrayOf(0xF8.toByte(), 0x00)
        val green565 = byteArrayOf(0x07, 0xE0.toByte())
        val batch =
            encodeNativeFrameBatch(
                frame(
                    sequence = 1,
                    fullRefresh = true,
                    tiles = listOf(NativeTile(0, 0, 0, 0, 4, 2, ByteArray(4 * 2 * 2))),
                ),
                frame(
                    sequence = 2,
                    operations =
                        listOf(
                            DisplayFrameOperation.FillRect(x = 0, y = 0, width = 2, height = 1, rgb565 = 0xF800),
                            DisplayFrameOperation.CopyRect(srcX = 0, srcY = 0, width = 2, height = 1, dstX = 2, dstY = 1),
                        ),
                    tiles = listOf(NativeTile(1, 0, 1, 0, 1, 1, green565)),
                ),
            )

        assertTrue(buffer.applyNativeFrameBatch(batch))
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
                ClientDisplayBuffer.Region(0, 0, 4, 2),
                ClientDisplayBuffer.Region(0, 0, 2, 1),
                ClientDisplayBuffer.Region(2, 1, 2, 1),
                ClientDisplayBuffer.Region(1, 0, 1, 1),
            ),
            buffer.frontDirtyRegions(),
        )
    }

    @Test
    fun nativeAndDecodedMonoBlitsProduceIdenticalPixelsAndTilesOverrideOperations() {
        val mono =
            DisplayFrameOperation.MonoBlit(
                x = 0,
                y = 0,
                width = 5,
                height = 2,
                foregroundRgb565 = 0xffff,
                backgroundRgb565 = 0x001f,
                packedMask = byteArrayOf(0b1010_1000.toByte(), 0b0101_0000),
            )
        val green565 = byteArrayOf(0x07, 0xE0.toByte())
        val decodedBuffer = ClientDisplayBuffer(displayId = 1, width = 5, height = 2)
        val nativeBuffer = ClientDisplayBuffer(displayId = 1, width = 5, height = 2)
        val decodedFrame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 1,
                width = 5,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = listOf(DisplayTile(0, 0, 0, 0, 1, 1, green565)),
                operations = listOf(mono),
            )
        val nativeBatch =
            encodeNativeFrameBatch(
                frame(
                    sequence = 1,
                    operations = listOf(mono),
                    tiles = listOf(NativeTile(0, 0, 0, 0, 1, 1, green565)),
                ),
                displayWidth = 5,
                displayHeight = 2,
            )

        assertTrue(decodedBuffer.apply(decodedFrame))
        assertTrue(nativeBuffer.applyNativeFrameBatch(nativeBatch))
        assertTrue(decodedBuffer.swapIfDirty())
        assertTrue(nativeBuffer.swapIfDirty())

        assertEquals(decodedBuffer.frontArgb().toList(), nativeBuffer.frontArgb().toList())
        assertEquals(0xFF00FF00.toInt(), nativeBuffer.frontArgb().first())
        assertEquals(
            listOf(
                0xFF00FF00.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFFFFF.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFFFFF.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFFFFF.toInt(),
                0xFF0000FF.toInt(),
                0xFFFFFFFF.toInt(),
                0xFF0000FF.toInt(),
            ),
            nativeBuffer.frontArgb().toList(),
        )
    }

    @Test
    fun copyRectScratchGrowsOnceAndIsReused() {
        val buffer = ClientDisplayBuffer(displayId = 1, width = 4, height = 2)
        val initial =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 1,
                width = 4,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = true,
                tiles = listOf(DisplayTile(0, 0, 0, 0, 4, 2, ByteArray(16))),
            )
        val largeCopy =
            initial.copy(
                sequence = 2,
                fullRefresh = false,
                tiles = emptyList(),
                operations = listOf(DisplayFrameOperation.CopyRect(0, 0, 3, 2, 1, 0)),
            )
        val smallCopy =
            largeCopy.copy(
                sequence = 3,
                operations = listOf(DisplayFrameOperation.CopyRect(0, 0, 1, 1, 3, 1)),
            )

        assertTrue(buffer.apply(initial))
        assertTrue(buffer.apply(largeCopy))
        assertEquals(6, buffer.copyRectScratchCapacityForTests)
        assertTrue(buffer.apply(smallCopy))
        assertEquals(6, buffer.copyRectScratchCapacityForTests)
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

    private data class NativeFrame(
        val sequence: Long,
        val fullRefresh: Boolean = false,
        val operations: List<DisplayFrameOperation> = emptyList(),
        val tiles: List<NativeTile> = emptyList(),
    )

    private data class NativeTile(
        val tileX: Int,
        val tileY: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val payload: ByteArray,
    )

    private fun frame(
        sequence: Long,
        fullRefresh: Boolean = false,
        operations: List<DisplayFrameOperation> = emptyList(),
        tiles: List<NativeTile> = emptyList(),
    ): NativeFrame =
        NativeFrame(
            sequence = sequence,
            fullRefresh = fullRefresh,
            operations = operations,
            tiles = tiles,
        )

    private fun encodeNativeFrameBatch(
        vararg frames: NativeFrame,
        displayWidth: Int = 4,
        displayHeight: Int = 2,
    ): ByteArray {
        val payloadBytes = frames.sumOf { frame -> frame.tiles.sumOf { it.payload.size } }
        val operationBytes =
            frames.sumOf { frame ->
                frame.operations.sumOf { operation ->
                    when (operation) {
                        is DisplayFrameOperation.FillRect -> 1 + 5 * 4
                        is DisplayFrameOperation.CopyRect -> 1 + 6 * 4
                        is DisplayFrameOperation.MonoBlit -> 1 + 7 * 4 + operation.packedMask.size
                    }
                }
            }
        val buffer =
            ByteBuffer
                .allocate(4 + frames.size * 35 + frames.sumOf { it.tiles.size * 28 } + payloadBytes + operationBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(frames.size)
        for (frame in frames) {
            buffer
                .putInt(1)
                .putLong(frame.sequence)
                .putInt(displayWidth)
                .putInt(displayHeight)
                .put(0)
                .put(if (frame.fullRefresh) 1 else 0)
                .putInt(frame.tiles.size)
            for (tile in frame.tiles) {
                buffer
                    .putInt(tile.tileX)
                    .putInt(tile.tileY)
                    .putInt(tile.x)
                    .putInt(tile.y)
                    .putInt(tile.width)
                    .putInt(tile.height)
                    .putInt(tile.payload.size)
                    .put(tile.payload)
            }
            buffer.putInt(frame.operations.size)
            for (operation in frame.operations) {
                when (operation) {
                    is DisplayFrameOperation.FillRect -> {
                        buffer
                            .put(1)
                            .putInt(operation.x)
                            .putInt(operation.y)
                            .putInt(operation.width)
                            .putInt(operation.height)
                            .putInt(operation.rgb565)
                    }

                    is DisplayFrameOperation.CopyRect -> {
                        buffer
                            .put(2)
                            .putInt(operation.srcX)
                            .putInt(operation.srcY)
                            .putInt(operation.width)
                            .putInt(operation.height)
                            .putInt(operation.dstX)
                            .putInt(operation.dstY)
                    }

                    is DisplayFrameOperation.MonoBlit -> {
                        buffer
                            .put(3)
                            .putInt(operation.x)
                            .putInt(operation.y)
                            .putInt(operation.width)
                            .putInt(operation.height)
                            .putInt(operation.foregroundRgb565)
                            .putInt(operation.backgroundRgb565)
                            .putInt(operation.packedMask.size)
                            .put(operation.packedMask)
                    }
                }
            }
        }
        return buffer.array()
    }
}
