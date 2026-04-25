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
import ru.lazyhat.compukterkraft.common.computer.network.client.StdoutBytesClientMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.AttachTerminalServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerActionServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.KeyEventServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.MouseEventServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.PasteEventComputerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.ResizeTerminalServerMessage
import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext
import ru.lazyhat.compukterkraft.common.network.text.ChatTableClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchDocumentSnapshotClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchOpsClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchTerminalClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchWorkspaceClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchInputServerMessage
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchOpsServerMessage
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchWorkspaceServerMessage

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
 * | 4  | unused                       | —                                  | Reserved for removed computer workspace requests | — |
 * | 5  | `workbench_workspace_request` | [WorkbenchWorkspaceServerMessage] | Workbench editor requests file, sync, or target actions | Workbench authoring session; triggers clientbound response |
 * | 6  | `workbench_input`            | [WorkbenchInputServerMessage]     | Player sends terminal key/mouse/paste input through the Workbench | Target VM event queue via Workbench runtime bridge |
 *
 * ### Server → Client (clientbound)
 *
 * | ID | Channel              | Class                              | Trigger                                        | State modified on client                      |
 * |----|----------------------|------------------------------------|-------------------------------------------------|-----------------------------------------------|
 * | 10 | `chat_table`         | [ChatTableClientMessage]           | Server sends a formatted table to display in chat | Minecraft chat HUD                            |
 * | 13 | unused               | —                                  | Reserved for removed ComputerTerminalClientMessage (Epic 4)     | —                                             |
 * | 14 | `stdout_bytes`       | [StdoutBytesClientMessage]         | Server flushes pending VM stdout bytes to attached terminal session | [ClientTerminalBuffer.feed]               |
 * | 15 | `workbench_workspace` | [WorkbenchWorkspaceClientMessage] | Response to a Workbench action or workspace request | [AbstractWorkbenchMenu.updateRemoteState] |
 * | 16 | `workbench_terminal`  | [WorkbenchTerminalClientMessage]  | Target terminal snapshot changed while Workbench is open | [AbstractWorkbenchMenu.updateScreenSnapshot] |
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
    val WORKBENCH_WORKSPACE_REQUEST: MessageType<WorkbenchWorkspaceServerMessage> =
        registerServerbound(
            5,
            "workbench_workspace_request",
            { buf -> WorkbenchWorkspaceServerMessage(buf) },
        )
    val WORKBENCH_INPUT: MessageType<WorkbenchInputServerMessage> =
        registerServerbound(
            6,
            "workbench_input",
            { buf -> WorkbenchInputServerMessage(buf) },
        )
    val WORKBENCH_OPS_REQUEST: MessageType<WorkbenchOpsServerMessage> =
        registerServerbound(
            9,
            "workbench_ops_request",
            { buf -> WorkbenchOpsServerMessage(buf) },
        )
    val ATTACH_TERMINAL: MessageType<AttachTerminalServerMessage> =
        registerServerbound(
            7,
            "attach_terminal",
            { buf -> AttachTerminalServerMessage(buf) },
        )
    val RESIZE_TERMINAL: MessageType<ResizeTerminalServerMessage> =
        registerServerbound(
            8,
            "resize_terminal",
            { buf -> ResizeTerminalServerMessage(buf) },
        )
    val CHAT_TABLE: MessageType<ChatTableClientMessage> =
        registerClientbound(
            10,
            "chat_table",
            { buf -> ChatTableClientMessage(buf) },
        )
    val STDOUT_BYTES: MessageType<StdoutBytesClientMessage> =
        registerClientbound(
            14,
            "stdout_bytes",
            { buf -> StdoutBytesClientMessage(buf) },
        )
    val WORKBENCH_WORKSPACE: MessageType<WorkbenchWorkspaceClientMessage> =
        registerClientbound(
            15,
            "workbench_workspace",
            { buf -> WorkbenchWorkspaceClientMessage(buf) },
        )
    val WORKBENCH_TERMINAL: MessageType<WorkbenchTerminalClientMessage> =
        registerClientbound(
            16,
            "workbench_terminal",
            { buf -> WorkbenchTerminalClientMessage(buf) },
        )
    val WORKBENCH_OPS: MessageType<WorkbenchOpsClientMessage> =
        registerClientbound(
            17,
            "workbench_ops",
            { buf -> WorkbenchOpsClientMessage(buf) },
        )
    val WORKBENCH_DOCUMENT_SNAPSHOT: MessageType<WorkbenchDocumentSnapshotClientMessage> =
        registerClientbound(
            18,
            "workbench_document_snapshot",
            { buf -> WorkbenchDocumentSnapshotClientMessage(buf) },
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
