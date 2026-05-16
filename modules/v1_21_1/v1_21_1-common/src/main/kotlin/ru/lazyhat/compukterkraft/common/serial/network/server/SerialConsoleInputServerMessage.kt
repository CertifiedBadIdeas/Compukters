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
package ru.lazyhat.compukterkraft.common.serial.network.server

import io.netty.handler.codec.DecoderException
import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext
import ru.lazyhat.compukterkraft.common.serial.menu.SerialTerminalMenu
import ru.lazyhat.compukterkraft.core.utils.StringUtil

class SerialConsoleInputServerMessage : NetworkMessage<ServerNetworkContext> {
    val containerId: Int
    val bytes: ByteArray

    constructor(
        containerId: Int,
        bytes: ByteArray,
    ) {
        this.containerId = containerId
        this.bytes = bytes.copyOf()
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        val length = buf.readVarInt()
        if (length > StringUtil.MAX_PASTE_LENGTH) {
            throw DecoderException("Serial input size $length is bigger than allowed ${StringUtil.MAX_PASTE_LENGTH}")
        }
        bytes = ByteArray(length)
        buf.readBytes(bytes)
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeVarInt(bytes.size)
        buf.writeBytes(bytes)
    }

    override fun handle(context: ServerNetworkContext) {
        val menu = context.sender().containerMenu as? SerialTerminalMenu ?: return
        if (menu.containerId == containerId) {
            menu.pushSerialInput(bytes)
        }
    }

    override fun type(): MessageType<SerialConsoleInputServerMessage> = NetworkMessages.SERIAL_CONSOLE_INPUT
}
