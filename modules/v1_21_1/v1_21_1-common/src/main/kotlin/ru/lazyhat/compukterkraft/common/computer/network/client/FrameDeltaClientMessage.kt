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

package ru.lazyhat.compukterkraft.common.computer.network.client

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameOperation
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile

class FrameDeltaClientMessage : NetworkMessage<ClientNetworkContext> {
    val containerId: Int
    val frame: DisplayFrameDelta

    constructor(containerId: Int, frame: DisplayFrameDelta) {
        this.containerId = containerId
        this.frame = frame
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        val displayId = buf.readVarInt()
        val sequence = buf.readLong()
        val width = buf.readVarInt()
        val height = buf.readVarInt()
        val format = DisplayPixelFormat.entries[buf.readVarInt()]
        val fullRefresh = buf.readBoolean()
        val tiles =
            List(buf.readVarInt()) {
                DisplayTile(
                    tileX = buf.readVarInt(),
                    tileY = buf.readVarInt(),
                    x = buf.readVarInt(),
                    y = buf.readVarInt(),
                    width = buf.readVarInt(),
                    height = buf.readVarInt(),
                    payload = buf.readByteArray(),
                )
            }
        val operations =
            List(buf.readVarInt()) {
                when (val operation = buf.readVarInt()) {
                    1 -> {
                        DisplayFrameOperation.FillRect(
                            x = buf.readVarInt(),
                            y = buf.readVarInt(),
                            width = buf.readVarInt(),
                            height = buf.readVarInt(),
                            rgb565 = buf.readVarInt(),
                        )
                    }

                    2 -> {
                        DisplayFrameOperation.CopyRect(
                            srcX = buf.readVarInt(),
                            srcY = buf.readVarInt(),
                            width = buf.readVarInt(),
                            height = buf.readVarInt(),
                            dstX = buf.readVarInt(),
                            dstY = buf.readVarInt(),
                        )
                    }

                    3 -> {
                        val x = buf.readVarInt()
                        val y = buf.readVarInt()
                        val width = buf.readVarInt()
                        val height = buf.readVarInt()
                        val foregroundRgb565 = buf.readVarInt()
                        val backgroundRgb565 = buf.readVarInt()
                        val packedMask = buf.readByteArray()
                        require(packedMask.size == packedMonoMaskLength(width, height)) {
                            "Mono mask payload length ${packedMask.size} does not match dimensions ${width}x$height"
                        }
                        DisplayFrameOperation.MonoBlit(
                            x = x,
                            y = y,
                            width = width,
                            height = height,
                            foregroundRgb565 = foregroundRgb565,
                            backgroundRgb565 = backgroundRgb565,
                            packedMask = packedMask,
                        )
                    }

                    else -> {
                        error("Unknown display frame operation $operation")
                    }
                }
            }
        frame = DisplayFrameDelta(displayId, sequence, width, height, format, fullRefresh, tiles, operations)
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeVarInt(frame.displayId)
        buf.writeLong(frame.sequence)
        buf.writeVarInt(frame.width)
        buf.writeVarInt(frame.height)
        buf.writeVarInt(frame.pixelFormat.ordinal)
        buf.writeBoolean(frame.fullRefresh)
        buf.writeVarInt(frame.tiles.size)
        for (tile in frame.tiles) {
            buf.writeVarInt(tile.tileX)
            buf.writeVarInt(tile.tileY)
            buf.writeVarInt(tile.x)
            buf.writeVarInt(tile.y)
            buf.writeVarInt(tile.width)
            buf.writeVarInt(tile.height)
            buf.writeByteArray(tile.payload)
        }
        buf.writeVarInt(frame.operations.size)
        for (operation in frame.operations) {
            when (operation) {
                is DisplayFrameOperation.FillRect -> {
                    buf.writeVarInt(1)
                    buf.writeVarInt(operation.x)
                    buf.writeVarInt(operation.y)
                    buf.writeVarInt(operation.width)
                    buf.writeVarInt(operation.height)
                    buf.writeVarInt(operation.rgb565)
                }

                is DisplayFrameOperation.CopyRect -> {
                    buf.writeVarInt(2)
                    buf.writeVarInt(operation.srcX)
                    buf.writeVarInt(operation.srcY)
                    buf.writeVarInt(operation.width)
                    buf.writeVarInt(operation.height)
                    buf.writeVarInt(operation.dstX)
                    buf.writeVarInt(operation.dstY)
                }

                is DisplayFrameOperation.MonoBlit -> {
                    require(operation.packedMask.size == packedMonoMaskLength(operation.width, operation.height)) {
                        "Mono mask payload length ${operation.packedMask.size} does not match dimensions " +
                            "${operation.width}x${operation.height}"
                    }
                    buf.writeVarInt(3)
                    buf.writeVarInt(operation.x)
                    buf.writeVarInt(operation.y)
                    buf.writeVarInt(operation.width)
                    buf.writeVarInt(operation.height)
                    buf.writeVarInt(operation.foregroundRgb565)
                    buf.writeVarInt(operation.backgroundRgb565)
                    buf.writeByteArray(operation.packedMask)
                }
            }
        }
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleDisplayFrame(containerId, frame)
    }

    override fun type(): MessageType<FrameDeltaClientMessage> = NetworkMessages.FRAME_DELTA

    private fun packedMonoMaskLength(
        width: Int,
        height: Int,
    ): Int {
        require(width > 0 && height > 0) {
            "Mono mask dimensions must be positive, got ${width}x$height"
        }
        val length = ((width.toLong() + 7L) / 8L) * height.toLong()
        require(length <= Int.MAX_VALUE) {
            "Mono mask payload length overflows Int"
        }
        return length.toInt()
    }
}
