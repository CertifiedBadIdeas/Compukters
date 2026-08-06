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
import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages

class RetainedDisplayStateClientMessage : NetworkMessage<ClientNetworkContext> {
    val computerId: Int
    val payload: ByteArray

    constructor(computerId: Int, payload: ByteArray) {
        requireComputerId(computerId)
        require(payload.size <= MAX_RETAINED_DISPLAY_PAYLOAD_BYTES) {
            "Retained display state payload exceeds $MAX_RETAINED_DISPLAY_PAYLOAD_BYTES bytes"
        }
        this.computerId = computerId
        this.payload = payload.copyOf()
    }

    constructor(buffer: FriendlyByteBuf) :
        this(buffer.readVarInt(), buffer.readByteArray(MAX_RETAINED_DISPLAY_PAYLOAD_BYTES))

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(computerId)
        buf.writeByteArray(payload)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleRetainedDisplayState(computerId, payload)
    }

    override fun type(): MessageType<RetainedDisplayStateClientMessage> = NetworkMessages.RETAINED_DISPLAY_STATE
}
