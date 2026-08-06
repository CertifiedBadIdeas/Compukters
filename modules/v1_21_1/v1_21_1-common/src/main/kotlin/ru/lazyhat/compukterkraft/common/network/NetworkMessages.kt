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
package ru.lazyhat.compukterkraft.common.network

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayAttachServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayControlServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayDetachServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayStateClientMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerActionServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.KeyEventServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.MouseEventServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.PasteEventComputerMessage
import ru.lazyhat.compukterkraft.common.network.text.ChatTableClientMessage

/**
 * Registry of all network message types used by the mod (CKL stack removed; computer-only).
 */
object NetworkMessages {
    private val seenIds: IntSet = IntOpenHashSet()
    private val seenChannel = mutableSetOf<String>()
    private val serverMessages = mutableListOf<MessageType<out NetworkMessage<ServerNetworkContext>>>()
    private val clientMessages = mutableListOf<MessageType<out NetworkMessage<ClientNetworkContext>>>()

    val COMPUTER_ACTION: MessageType<ComputerActionServerMessage> =
        registerServerbound(0, "computer_action") { ComputerActionServerMessage(it) }
    val KEY_EVENT: MessageType<KeyEventServerMessage> =
        registerServerbound(1, "key_event") { KeyEventServerMessage(it) }
    val MOUSE_EVENT: MessageType<MouseEventServerMessage> =
        registerServerbound(2, "mouse_event") { MouseEventServerMessage(it) }
    val PASTE_EVENT: MessageType<PasteEventComputerMessage> =
        registerServerbound(3, "paste_event") { PasteEventComputerMessage(it) }
    val CHAT_TABLE: MessageType<ChatTableClientMessage> =
        registerClientbound(10, "chat_table") { ChatTableClientMessage(it) }
    val RETAINED_DISPLAY_ATTACH: MessageType<RetainedDisplayAttachServerMessage> =
        registerServerbound(22, "retained_display_attach") { RetainedDisplayAttachServerMessage(it) }
    val RETAINED_DISPLAY_DETACH: MessageType<RetainedDisplayDetachServerMessage> =
        registerServerbound(23, "retained_display_detach") { RetainedDisplayDetachServerMessage(it) }
    val RETAINED_DISPLAY_CONTROL: MessageType<RetainedDisplayControlServerMessage> =
        registerServerbound(24, "retained_display_control") { RetainedDisplayControlServerMessage(it) }
    val RETAINED_DISPLAY_STATE: MessageType<RetainedDisplayStateClientMessage> =
        registerClientbound(25, "retained_display_state") { RetainedDisplayStateClientMessage(it) }

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
