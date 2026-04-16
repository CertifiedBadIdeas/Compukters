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

package ru.lazyhat.compukterkraft.common.workbench.network.client

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchSyncState
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchTargetState
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry

class WorkbenchWorkspaceClientMessage : NetworkMessage<ClientNetworkContext> {
    private val containerId: Int
    private val remoteState: WorkbenchRemoteState

    constructor(
        containerId: Int,
        remoteState: WorkbenchRemoteState,
    ) {
        this.containerId = containerId
        this.remoteState = remoteState
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()

        val entries =
            List(buf.readVarInt()) {
                ComputerWorkspaceEntry(
                    path = buf.readUtf(),
                    directory = buf.readBoolean(),
                    size = buf.readVarInt(),
                    version = buf.readVarLong(),
                )
            }

        val document =
            if (!buf.readBoolean()) {
                null
            } else {
                ComputerWorkspaceDocument(
                    path = buf.readUtf(),
                    text = buf.readUtf(Short.MAX_VALUE.toInt()),
                    version = buf.readVarLong(),
                )
            }

        val target =
            WorkbenchTargetState(
                connected = buf.readBoolean(),
                displayName = if (buf.readBoolean()) buf.readUtf() else null,
                familyId = if (buf.readBoolean()) buf.readUtf() else null,
            )

        val sync = WorkbenchSyncState(dirtyLocal = buf.readBoolean(), dirtyRemote = buf.readBoolean())

        remoteState = WorkbenchRemoteState(entries = entries, document = document, target = target, sync = sync)
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)

        buf.writeVarInt(remoteState.entries.size)
        remoteState.entries.forEach { entry ->
            buf.writeUtf(entry.path)
            buf.writeBoolean(entry.directory)
            buf.writeVarInt(entry.size)
            buf.writeVarLong(entry.version)
        }

        buf.writeBoolean(remoteState.document != null)
        remoteState.document?.let { document ->
            buf.writeUtf(document.path)
            buf.writeUtf(document.text, Short.MAX_VALUE.toInt())
            buf.writeVarLong(document.version)
        }

        buf.writeBoolean(remoteState.target.connected)
        buf.writeBoolean(remoteState.target.displayName != null)
        remoteState.target.displayName?.let(buf::writeUtf)
        buf.writeBoolean(remoteState.target.familyId != null)
        remoteState.target.familyId?.let(buf::writeUtf)

        buf.writeBoolean(remoteState.sync.dirtyLocal)
        buf.writeBoolean(remoteState.sync.dirtyRemote)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleWorkbenchWorkspace(containerId, remoteState)
    }

    override fun type(): MessageType<WorkbenchWorkspaceClientMessage> = NetworkMessages.WORKBENCH_WORKSPACE
}