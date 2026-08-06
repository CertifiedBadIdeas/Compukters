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

class RetainedDisplayDetachServerMessage : NetworkMessage<ServerNetworkContext> {
    val computerId: Int

    constructor(computerId: Int) {
        requireComputerId(computerId)
        this.computerId = computerId
    }

    constructor(buffer: FriendlyByteBuf) : this(buffer.readVarInt())

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(computerId)
    }

    override fun handle(context: ServerNetworkContext) {
        ServerContext.get(computerId)?.detachRetainedDisplayViewer(context.sender().uuid)
    }

    override fun type(): MessageType<RetainedDisplayDetachServerMessage> = NetworkMessages.RETAINED_DISPLAY_DETACH
}
