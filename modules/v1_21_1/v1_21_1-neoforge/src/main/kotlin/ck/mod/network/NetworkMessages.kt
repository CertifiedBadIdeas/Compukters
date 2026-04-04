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
package ck.mod.network

import ck.mod.network.client.ChatTableClientMessage
import ck.mod.network.client.ClientNetworkContext
import ck.mod.network.client.ComputerTerminalClientMessage
import ck.mod.network.client.ComputerWorkspaceClientMessage
import ck.mod.network.server.ComputerActionServerMessage
import ck.mod.network.server.ComputerWorkspaceServerMessage
import ck.mod.network.server.KeyEventServerMessage
import ck.mod.network.server.MouseEventServerMessage
import ck.mod.network.server.PasteEventComputerMessage
import ck.mod.network.server.ServerNetworkContext
import ck.mod.platform.NetworkHandler
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import net.minecraft.network.FriendlyByteBuf

/**
 * Registry of all network message types used by the mod.
 *
 * ## Packet Protocol
 *
 * ### Client → Server (serverbound)
 *
 * | ID | Channel                      | Class                              | Trigger                                           | State modified on server                     |
 * |----|------------------------------|------------------------------------|----------------------------------------------------|----------------------------------------------|
 * | 0  | `computer_action`            | [ComputerActionServerMessage]      | Player clicks Turn On / Shutdown / Reboot / Terminate button | [ServerComputer] lifecycle (turnOn/shutdown/reboot) |
 * | 1  | `key_event`                  | [KeyEventServerMessage]            | Player presses/releases a key while computer GUI is open | VM event queue (`key` / `key_up`)            |
 * | 2  | `mouse_event`                | [MouseEventServerMessage]          | Player clicks/drags/scrolls inside the terminal area | VM event queue (`mouse_click` / `mouse_up` / `mouse_drag` / `mouse_scroll`) |
 * | 3  | `paste_event`                | [PasteEventComputerMessage]        | Player pastes text (Ctrl+V)                        | VM event queue (`paste`)                     |
 * | 4  | `computer_workspace_request` | [ComputerWorkspaceServerMessage]   | IDE panel requests file list, reads or writes a document | Workspace filesystem; triggers clientbound response |
 *
 * ### Server → Client (clientbound)
 *
 * | ID | Channel              | Class                              | Trigger                                        | State modified on client                      |
 * |----|----------------------|------------------------------------|-------------------------------------------------|-----------------------------------------------|
 * | 10 | `chat_table`         | [ChatTableClientMessage]           | Server sends a formatted table to display in chat | Minecraft chat HUD                            |
 * | 13 | `computer_terminal`  | [ComputerTerminalClientMessage]    | Screen buffer dirty flag set during [ServerComputer.serverTick] | [ComputerMenu.updateTerminal] → client-side [ScreenBufferSnapshot] |
 * | 14 | `computer_workspace` | [ComputerWorkspaceClientMessage]   | Response to a workspace request (LIST/READ/WRITE) | [ComputerMenu.updateWorkspaceEntries] / [ComputerMenu.updateWorkspaceDocument] |
 */
object NetworkMessages {
    private val seenIds: IntSet = IntOpenHashSet()
    private val seenChannel = mutableSetOf<String>()
    private val serverMessages = mutableListOf<MessageType<out NetworkMessage<ServerNetworkContext>>>()
    private val clientMessages = mutableListOf<MessageType<out NetworkMessage<ClientNetworkContext>>>()

    val COMPUTER_ACTION: MessageType<ComputerActionServerMessage> =
        registerServerbound(
            0,
            "computer_action",
            { buf -> ComputerActionServerMessage(buf) },
        )
    val KEY_EVENT: MessageType<KeyEventServerMessage> =
        registerServerbound(
            1,
            "key_event",
            { buf -> KeyEventServerMessage(buf) },
        )
    val MOUSE_EVENT: MessageType<MouseEventServerMessage> =
        registerServerbound(
            2,
            "mouse_event",
            { buf -> MouseEventServerMessage(buf) },
        )
    val PASTE_EVENT: MessageType<PasteEventComputerMessage> =
        registerServerbound(
            3,
            "paste_event",
            { buf -> PasteEventComputerMessage(buf) },
        )
    val COMPUTER_WORKSPACE_REQUEST: MessageType<ComputerWorkspaceServerMessage> =
        registerServerbound(
            4,
            "computer_workspace_request",
            { buf -> ComputerWorkspaceServerMessage(buf) },
        )
    val CHAT_TABLE: MessageType<ChatTableClientMessage> =
        registerClientbound(
            10,
            "chat_table",
            { buf -> ChatTableClientMessage(buf) },
        )
    val COMPUTER_TERMINAL: MessageType<ComputerTerminalClientMessage> =
        registerClientbound(
            13,
            "computer_terminal",
            { buf -> ComputerTerminalClientMessage(buf) },
        )
    val COMPUTER_WORKSPACE: MessageType<ComputerWorkspaceClientMessage> =
        registerClientbound(
            14,
            "computer_workspace",
            { buf -> ComputerWorkspaceClientMessage(buf) },
        )

    @Suppress("UNCHECKED_CAST")
    private fun <C, T : NetworkMessage<C>> register(
        messages: MutableList<MessageType<out NetworkMessage<C>>>,
        id: Int,
        channel: String,
        reader: (FriendlyByteBuf) -> T,
    ): MessageType<T> {
        require(seenIds.add(id)) { "Duplicate id $id" }
        require(seenChannel.add(channel)) { "Duplicate channel $channel" }
        val type = NetworkHandler.MessageTypeImpl(id, reader)
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

    internal fun serverboundById(id: Int): NetworkHandler.MessageTypeImpl<out NetworkMessage<ServerNetworkContext>> =
        serverMessages
            .firstOrNull { (it as NetworkHandler.MessageTypeImpl<out NetworkMessage<ServerNetworkContext>>).id == id }
            ?.let { it as NetworkHandler.MessageTypeImpl<out NetworkMessage<ServerNetworkContext>> }
            ?: error("Unknown serverbound message id: $id")

    internal fun clientboundById(id: Int): NetworkHandler.MessageTypeImpl<out NetworkMessage<ClientNetworkContext>> =
        clientMessages
            .firstOrNull { (it as NetworkHandler.MessageTypeImpl<out NetworkMessage<ClientNetworkContext>>).id == id }
            ?.let { it as NetworkHandler.MessageTypeImpl<out NetworkMessage<ClientNetworkContext>> }
            ?: error("Unknown clientbound message id: $id")

    /**
     * Get all serverbound message types.
     *
     * @return An unmodifiable sequence of all serverbound message types.
     */
    val serverbound: List<MessageType<out NetworkMessage<ServerNetworkContext>>>
        get() = serverMessages.toList()

    /**
     * Get all clientbound message types.
     *
     * @return An unmodifiable sequence of all clientbound message types.
     */
    val clientbound: List<MessageType<out NetworkMessage<ClientNetworkContext>>>
        get() = clientMessages.toList()
}
