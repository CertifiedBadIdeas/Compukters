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

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameOperation
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class NativeDisplayFrameBatchSummary(
    val frameCount: Int,
    val tileCount: Int,
    val payloadBytes: Int,
    val operationCount: Int,
)

object NativeDisplayFrameCodec {
    fun mergeFrameBatches(batches: List<ByteArray>): ByteArray {
        val nonEmptyBatches = batches.filter { it.isNotEmpty() }
        if (nonEmptyBatches.isEmpty()) return ByteArray(0)

        val scans = nonEmptyBatches.map(::scanFrames)
        val output = ByteArray(4 + scans.sumOf { it.endOffset - 4 })
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).putInt(scans.sumOf { it.frameCount })
        var outputOffset = 4
        for ((index, bytes) in nonEmptyBatches.withIndex()) {
            val endOffset = scans[index].endOffset
            bytes.copyInto(output, destinationOffset = outputOffset, startIndex = 4, endIndex = endOffset)
            outputOffset += endOffset - 4
        }
        return output
    }

    fun summarizeFrames(bytes: ByteArray): NativeDisplayFrameBatchSummary {
        val scan = scanFrames(bytes)
        return NativeDisplayFrameBatchSummary(
            frameCount = scan.frameCount,
            tileCount = scan.tileCount,
            payloadBytes = scan.payloadBytes,
            operationCount = scan.operationCount,
        )
    }

    private fun scanFrames(bytes: ByteArray): NativeFrameBatchScan {
        if (bytes.isEmpty()) {
            return NativeFrameBatchScan(frameCount = 0, tileCount = 0, payloadBytes = 0, operationCount = 0, endOffset = 0)
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = input.int
        var tileCountTotal = 0
        var payloadBytesTotal = 0
        var operationCountTotal = 0
        repeat(frameCount) {
            input.position(input.position() + 4 + 8 + 4 + 4 + 1 + 1)
            val tileCount = input.int
            tileCountTotal += tileCount
            repeat(tileCount) {
                input.position(input.position() + 6 * 4)
                val payloadLength = input.int
                payloadBytesTotal += payloadLength
                input.position(input.position() + payloadLength)
            }
            val operationCount = input.int
            operationCountTotal += operationCount
            repeat(operationCount) {
                when (val operation = input.get().toInt()) {
                    1 -> input.position(input.position() + 5 * 4)
                    2 -> input.position(input.position() + 6 * 4)
                    else -> error("Unknown native display operation $operation")
                }
            }
        }
        return NativeFrameBatchScan(
            frameCount = frameCount,
            tileCount = tileCountTotal,
            payloadBytes = payloadBytesTotal,
            operationCount = operationCountTotal,
            endOffset = input.position(),
        )
    }

    fun decodeFrames(bytes: ByteArray): List<DisplayFrameDelta> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = input.int
        return List(count) {
            val displayId = input.int
            val sequence = input.long
            val width = input.int
            val height = input.int
            val pixelFormat =
                when (input.get().toInt()) {
                    0 -> DisplayPixelFormat.RGB565
                    else -> error("Unknown native display pixel format")
                }
            val fullRefresh = input.get().toInt() != 0
            val tileCount = input.int
            val tiles =
                List(tileCount) {
                    val tileX = input.int
                    val tileY = input.int
                    val x = input.int
                    val y = input.int
                    val tileWidth = input.int
                    val tileHeight = input.int
                    val payloadLength = input.int
                    val payload = ByteArray(payloadLength)
                    input.get(payload)
                    DisplayTile(tileX, tileY, x, y, tileWidth, tileHeight, payload)
                }
            val operations =
                List(input.int) {
                    when (val operation = input.get().toInt()) {
                        1 -> {
                            DisplayFrameOperation.FillRect(
                                x = input.int,
                                y = input.int,
                                width = input.int,
                                height = input.int,
                                rgb565 = input.int,
                            )
                        }

                        2 -> {
                            DisplayFrameOperation.CopyRect(
                                srcX = input.int,
                                srcY = input.int,
                                width = input.int,
                                height = input.int,
                                dstX = input.int,
                                dstY = input.int,
                            )
                        }

                        else -> {
                            error("Unknown native display operation $operation")
                        }
                    }
                }
            DisplayFrameDelta(displayId, sequence, width, height, pixelFormat, fullRefresh, tiles, operations)
        }
    }

    private data class NativeFrameBatchScan(
        val frameCount: Int,
        val tileCount: Int,
        val payloadBytes: Int,
        val operationCount: Int,
        val endOffset: Int,
    )
}
