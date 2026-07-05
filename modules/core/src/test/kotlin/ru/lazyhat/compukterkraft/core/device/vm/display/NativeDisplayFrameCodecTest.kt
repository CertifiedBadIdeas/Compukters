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

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameOperation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeDisplayFrameCodecTest {
    @Test
    fun summarizesFramesWithoutDecodingTiles() {
        val bytes =
            ByteBuffer
                .allocate(4 + 26 + 28 + 4 + 4 + 1 + 5 * 4 + 26 + 4 + 1 + 6 * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(2)
                .putInt(7)
                .putLong(42)
                .putInt(320)
                .putInt(200)
                .put(0)
                .put(0)
                .putInt(1)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .putInt(2)
                .putInt(1)
                .putInt(4)
                .put(byteArrayOf(0xF8.toByte(), 0x00, 0x07, 0xE0.toByte()))
                .putInt(1)
                .put(1)
                .putInt(0)
                .putInt(192)
                .putInt(320)
                .putInt(8)
                .putInt(0x07E0)
                .putInt(7)
                .putLong(43)
                .putInt(320)
                .putInt(200)
                .put(0)
                .put(0)
                .putInt(0)
                .putInt(1)
                .put(2)
                .putInt(0)
                .putInt(8)
                .putInt(320)
                .putInt(192)
                .putInt(0)
                .putInt(0)
                .array()

        assertEquals(
            NativeDisplayFrameBatchSummary(
                frameCount = 2,
                tileCount = 1,
                payloadBytes = 4,
                operationCount = 2,
            ),
            NativeDisplayFrameCodec.summarizeFrames(bytes),
        )
    }

    @Test
    fun decodesDisplayFrameOperationsAfterTiles() {
        val bytes =
            ByteBuffer
                .allocate(4 + 4 + 8 + 4 + 4 + 1 + 1 + 4 + 4 + 1 + 5 * 4 + 1 + 6 * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1)
                .putInt(7)
                .putLong(42)
                .putInt(320)
                .putInt(200)
                .put(0)
                .put(0)
                .putInt(0)
                .putInt(2)
                .put(1)
                .putInt(0)
                .putInt(192)
                .putInt(320)
                .putInt(8)
                .putInt(0x07E0)
                .put(2)
                .putInt(0)
                .putInt(8)
                .putInt(320)
                .putInt(192)
                .putInt(0)
                .putInt(0)
                .array()

        val frame = NativeDisplayFrameCodec.decodeFrames(bytes).single()

        assertEquals(
            listOf(
                DisplayFrameOperation.FillRect(x = 0, y = 192, width = 320, height = 8, rgb565 = 0x07E0),
                DisplayFrameOperation.CopyRect(srcX = 0, srcY = 8, width = 320, height = 192, dstX = 0, dstY = 0),
            ),
            frame.operations,
        )
    }

    @Test
    fun mergesNativeFrameBatchesWithoutDecodingFrames() {
        val rawFirstBatch =
            operationFrameBatch(
                sequence = 42,
                operation = DisplayFrameOperation.FillRect(x = 0, y = 192, width = 320, height = 8, rgb565 = 0x07E0),
            )
        val firstBatch = rawFirstBatch.copyOf(rawFirstBatch.size + 3)
        val secondBatch =
            operationFrameBatch(
                sequence = 43,
                operation =
                    DisplayFrameOperation.CopyRect(
                        srcX = 0,
                        srcY = 8,
                        width = 320,
                        height = 192,
                        dstX = 0,
                        dstY = 0,
                    ),
            )

        val merged = NativeDisplayFrameCodec.mergeFrameBatches(listOf(firstBatch, secondBatch))
        val frames = NativeDisplayFrameCodec.decodeFrames(merged)

        assertEquals(2, ByteBuffer.wrap(merged).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(
            NativeDisplayFrameBatchSummary(
                frameCount = 2,
                tileCount = 0,
                payloadBytes = 0,
                operationCount = 2,
            ),
            NativeDisplayFrameCodec.summarizeFrames(merged),
        )
        assertEquals(listOf(42L, 43L), frames.map { it.sequence })
        assertEquals(
            listOf(
                DisplayFrameOperation.FillRect(x = 0, y = 192, width = 320, height = 8, rgb565 = 0x07E0),
                DisplayFrameOperation.CopyRect(
                    srcX = 0,
                    srcY = 8,
                    width = 320,
                    height = 192,
                    dstX = 0,
                    dstY = 0,
                ),
            ),
            frames.flatMap { it.operations },
        )
    }

    private fun operationFrameBatch(
        sequence: Long,
        operation: DisplayFrameOperation,
    ): ByteArray {
        val operationBytes =
            when (operation) {
                is DisplayFrameOperation.FillRect -> 1 + 5 * 4
                is DisplayFrameOperation.CopyRect -> 1 + 6 * 4
            }
        val buffer =
            ByteBuffer
                .allocate(4 + 4 + 8 + 4 + 4 + 1 + 1 + 4 + 4 + operationBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1)
                .putInt(7)
                .putLong(sequence)
                .putInt(320)
                .putInt(200)
                .put(0)
                .put(0)
                .putInt(0)
                .putInt(1)
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
        }
        return buffer.array()
    }
}
