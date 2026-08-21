/*
 * The Compukters Developers
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
package ru.lazyhat.compukters.common.network

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukters.common.network.text.ChatTableClientMessage

/**
 * Registry of all network message types used by the loadable mod shell.
 */
object NetworkMessages {
    private val seenIds: IntSet = IntOpenHashSet()
    private val seenChannel = mutableSetOf<String>()
    private val serverMessages = mutableListOf<MessageType<out NetworkMessage<ServerNetworkContext>>>()
    private val clientMessages = mutableListOf<MessageType<out NetworkMessage<ClientNetworkContext>>>()

    val CHAT_TABLE: MessageType<ChatTableClientMessage> =
        registerClientbound(10, "chat_table") { ChatTableClientMessage(it) }

    @Suppress("UNCHECKED_CAST")
    private fun <C, T : NetworkMessage<C>> register(
        messages: MutableList<MessageType<out NetworkMessage<C>>>,
        id: Int,
        channel: String,
        reader: (FriendlyByteBuf) -> T,
    ): MessageType<T> {
        require(seenIds.add(id)) { "Duplicate id $id" }
        require(seenChannel.add(channel)) { "Duplicate channel $channel" }
        val type = MessageTypeImpl(id, reader)
        messages.add(type)
        return type
    }

    private fun <T : NetworkMessage<ServerNetworkContext>> registerServerbound(
        id: Int,
        channel: String,
        reader: (FriendlyByteBuf) -> T,
    ): MessageType<T> = register(serverMessages, id, channel, reader)

    private fun <T : NetworkMessage<ClientNetworkContext>> registerClientbound(
        id: Int,
        channel: String,
        reader: (FriendlyByteBuf) -> T,
    ): MessageType<T> = register(clientMessages, id, channel, reader)

    fun serverboundById(id: Int): MessageTypeImpl<out NetworkMessage<ServerNetworkContext>> =
        serverMessages
            .firstOrNull { (it as MessageTypeImpl<out NetworkMessage<ServerNetworkContext>>).id == id }
            ?.let { it as MessageTypeImpl<out NetworkMessage<ServerNetworkContext>> }
            ?: error("Unknown serverbound message id: $id")

    fun clientboundById(id: Int): MessageTypeImpl<out NetworkMessage<ClientNetworkContext>> =
        clientMessages
            .firstOrNull { (it as MessageTypeImpl<out NetworkMessage<ClientNetworkContext>>).id == id }
            ?.let { it as MessageTypeImpl<out NetworkMessage<ClientNetworkContext>> }
            ?: error("Unknown clientbound message id: $id")

    val serverbound: List<MessageType<out NetworkMessage<ServerNetworkContext>>>
        get() = serverMessages.toList()

    val clientbound: List<MessageType<out NetworkMessage<ClientNetworkContext>>>
        get() = clientMessages.toList()
}
