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
package ru.lazyhat.compukterkraft.common.serial.network.client

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages

class SerialConsoleOutputClientMessage : NetworkMessage<ClientNetworkContext> {
    val containerId: Int
    val bytes: ByteArray
    val reset: Boolean

    constructor(
        containerId: Int,
        bytes: ByteArray,
        reset: Boolean,
    ) {
        this.containerId = containerId
        this.bytes = bytes.copyOf()
        this.reset = reset
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        reset = buf.readBoolean()
        bytes = buf.readByteArray()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeBoolean(reset)
        buf.writeByteArray(bytes)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleSerialConsoleOutput(containerId, bytes, reset)
    }

    override fun type(): MessageType<SerialConsoleOutputClientMessage> = NetworkMessages.SERIAL_CONSOLE_OUTPUT
}
