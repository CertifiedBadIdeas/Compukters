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

class NativeFrameBatchClientMessage : NetworkMessage<ClientNetworkContext> {
    val containerId: Int
    val payload: ByteArray

    constructor(containerId: Int, payload: ByteArray) {
        this.containerId = containerId
        this.payload = payload
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        payload = buf.readByteArray()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeByteArray(payload)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleNativeDisplayFrameBytes(containerId, payload)
    }

    override fun type(): MessageType<NativeFrameBatchClientMessage> = NetworkMessages.NATIVE_FRAME_BATCH
}
