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

package ru.lazyhat.compukterkraft.common.computer.network.retained

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext

class RetainedDisplayControlServerMessage : NetworkMessage<ServerNetworkContext> {
    val computerId: Int
    val payload: ByteArray

    constructor(computerId: Int, payload: ByteArray) {
        requireComputerId(computerId)
        require(payload.size == ACK_BYTES || payload.size == RESYNC_BYTES) {
            "Retained display control payload must be $ACK_BYTES or $RESYNC_BYTES bytes"
        }
        this.computerId = computerId
        this.payload = payload.copyOf()
    }

    constructor(buffer: FriendlyByteBuf) : this(buffer.readVarInt(), buffer.readByteArray(RESYNC_BYTES))

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(computerId)
        buf.writeByteArray(payload)
    }

    override fun handle(context: ServerNetworkContext) {
        ServerContext.get(computerId)?.queueRetainedDisplayServerbound(context.sender().uuid, payload)
    }

    override fun type(): MessageType<RetainedDisplayControlServerMessage> = NetworkMessages.RETAINED_DISPLAY_CONTROL

    private companion object {
        const val ACK_BYTES = 32
        const val RESYNC_BYTES = 40
    }
}
