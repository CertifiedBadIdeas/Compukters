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
import kotlin.test.assertTrue

class ClientDisplayProfilingTest {
    @Test
    fun clientDisplayBufferRecordsApplySwapAndSnapshotWork() {
        val metrics = RecordingClientDisplayMetricsCollector()
        val buffer = ClientDisplayBuffer(displayId = 7, width = 2, height = 2, metricsCollector = metrics)
        val frame =
            DisplayFrameDelta(
                displayId = 7,
                sequence = 1,
                width = 2,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = true,
                tiles =
                    listOf(
                        DisplayTile(
                            tileX = 0,
                            tileY = 0,
                            x = 0,
                            y = 0,
                            width = 2,
                            height = 2,
                            payload = byteArrayOf(0, 0, 0, 1, 0, 2, 0, 3),
                        ),
                    ),
            )

        assertTrue(buffer.apply(frame))
        assertTrue(buffer.swapIfDirty())
        buffer.copyFrontChangesSince(uploadedVersion = 0, destination = IntArray(4))
        buffer.recordTextureUpload(regions = 1, pixels = 4, nanos = 7)

        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.framesApplied)
        assertEquals(1, snapshot.fullRefreshFrames)
        assertEquals(1, snapshot.tilesApplied)
        assertEquals(8, snapshot.payloadBytes)
        assertEquals(1, snapshot.swapCalls)
        assertEquals(1, snapshot.snapshotsCopied)
        assertEquals(4, snapshot.snapshotPixels)
        assertEquals(1, snapshot.textureUploads)
        assertEquals(1, snapshot.textureRegionsUploaded)
        assertEquals(4, snapshot.texturePixelsUploaded)
        assertEquals(7, snapshot.textureUploadNanos)
        assertTrue(snapshot.applyNanos >= 0)
        assertTrue(snapshot.swapNanos >= 0)
        assertTrue(snapshot.snapshotCopyNanos >= 0)
        assertTrue(snapshot.summary().contains("textureUpload: uploads=1, regions=1, pixels=4"))
    }

    @Test
    fun clientDisplayBufferRecordsNativeBatchApplyWork() {
        val metrics = RecordingClientDisplayMetricsCollector()
        val buffer = ClientDisplayBuffer(displayId = 7, width = 2, height = 2, metricsCollector = metrics)
        val frame =
            DisplayFrameDelta(
                displayId = 7,
                sequence = 1,
                width = 2,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles =
                    listOf(
                        DisplayTile(
                            tileX = 0,
                            tileY = 0,
                            x = 0,
                            y = 0,
                            width = 2,
                            height = 2,
                            payload = byteArrayOf(0, 0, 0, 1, 0, 2, 0, 3),
                        ),
                    ),
                operations =
                    listOf(
                        DisplayFrameOperation.FillRect(x = 0, y = 0, width = 1, height = 1, rgb565 = 0xFFFF),
                        DisplayFrameOperation.MonoBlit(
                            x = 0,
                            y = 1,
                            width = 2,
                            height = 1,
                            foregroundRgb565 = 0xffff,
                            backgroundRgb565 = 0,
                            packedMask = byteArrayOf(0x80.toByte()),
                        ),
                    ),
            )

        assertTrue(buffer.applyNativeFrameBatch(encodeDisplayFrames(listOf(frame))))

        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.nativeBatchesApplied)
        assertEquals(1, snapshot.nativeFramesApplied)
        assertEquals(1, snapshot.nativeTilesApplied)
        assertEquals(8, snapshot.nativePayloadBytes)
        assertEquals(2, snapshot.nativeOperationsApplied)
        assertEquals(1, snapshot.nativeMonoPayloadBytes)
        assertTrue(snapshot.nativeApplyNanos >= 0)
        assertTrue(snapshot.summary().contains("monoPayloadBytes=1"))
    }

    @Test
    fun clientDisplayBufferDoesNotCountRejectedNativeBatchWorkAsApplied() {
        val metrics = RecordingClientDisplayMetricsCollector()
        val buffer = ClientDisplayBuffer(displayId = 7, width = 2, height = 2, metricsCollector = metrics)
        val rejectedFrame =
            DisplayFrameDelta(
                displayId = 99,
                sequence = 1,
                width = 2,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles =
                    listOf(
                        DisplayTile(
                            tileX = 0,
                            tileY = 0,
                            x = 0,
                            y = 0,
                            width = 2,
                            height = 2,
                            payload = byteArrayOf(0, 0, 0, 1, 0, 2, 0, 3),
                        ),
                    ),
                operations =
                    listOf(
                        DisplayFrameOperation.FillRect(x = 0, y = 0, width = 1, height = 1, rgb565 = 0xFFFF),
                    ),
            )

        assertEquals(false, buffer.applyNativeFrameBatch(encodeDisplayFrames(listOf(rejectedFrame))))

        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.nativeBatchesApplied)
        assertEquals(0, snapshot.nativeFramesApplied)
        assertEquals(0, snapshot.nativeTilesApplied)
        assertEquals(0, snapshot.nativePayloadBytes)
        assertEquals(0, snapshot.nativeOperationsApplied)
    }

    private fun encodeDisplayFrames(frames: List<DisplayFrameDelta>): ByteArray {
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
                .allocate(4 + frames.size * 30 + frames.sumOf { it.tiles.size * 28 } + payloadBytes + operationBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(frames.size)
        for (frame in frames) {
            buffer.putInt(frame.displayId)
            buffer.putLong(frame.sequence)
            buffer.putInt(frame.width)
            buffer.putInt(frame.height)
            buffer.put(
                when (frame.pixelFormat) {
                    DisplayPixelFormat.RGB565 -> 0
                },
            )
            buffer.put(if (frame.fullRefresh) 1 else 0)
            buffer.putInt(frame.tiles.size)
            for (tile in frame.tiles) {
                buffer.putInt(tile.tileX)
                buffer.putInt(tile.tileY)
                buffer.putInt(tile.x)
                buffer.putInt(tile.y)
                buffer.putInt(tile.width)
                buffer.putInt(tile.height)
                buffer.putInt(tile.payload.size)
                buffer.put(tile.payload)
            }
            buffer.putInt(frame.operations.size)
            for (operation in frame.operations) {
                when (operation) {
                    is DisplayFrameOperation.FillRect -> {
                        buffer.put(1)
                        buffer.putInt(operation.x)
                        buffer.putInt(operation.y)
                        buffer.putInt(operation.width)
                        buffer.putInt(operation.height)
                        buffer.putInt(operation.rgb565)
                    }

                    is DisplayFrameOperation.CopyRect -> {
                        buffer.put(2)
                        buffer.putInt(operation.srcX)
                        buffer.putInt(operation.srcY)
                        buffer.putInt(operation.width)
                        buffer.putInt(operation.height)
                        buffer.putInt(operation.dstX)
                        buffer.putInt(operation.dstY)
                    }
                    is DisplayFrameOperation.MonoBlit -> {
                        buffer.put(3)
                        buffer.putInt(operation.x)
                        buffer.putInt(operation.y)
                        buffer.putInt(operation.width)
                        buffer.putInt(operation.height)
                        buffer.putInt(operation.foregroundRgb565)
                        buffer.putInt(operation.backgroundRgb565)
                        buffer.putInt(operation.packedMask.size)
                        buffer.put(operation.packedMask)
                    }
                }
            }
        }
        return buffer.array()
    }
}
