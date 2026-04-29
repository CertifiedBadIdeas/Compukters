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

package ru.lazyhat.compukterkraft.common.workbench.network.server

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.workbench.menu.AbstractWorkbenchMenu
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchWorkspaceClientMessage

class WorkbenchWorkspaceServerMessage : NetworkMessage<ServerNetworkContext> {
    private val containerId: Int
    private val action: Action
    private val path: String

    constructor(
        menu: AbstractContainerMenu,
        action: Action,
        path: String = "",
    ) {
        containerId = menu.containerId
        this.action = action
        this.path = path
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        action = buf.readEnum(Action::class.java)
        path = buf.readUtf()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeEnum(action)
        buf.writeUtf(path)
    }

    override fun handle(context: ServerNetworkContext) {
        val player = context.sender()
        val menu = player.containerMenu
        if (menu.containerId != containerId || menu !is AbstractWorkbenchMenu) return

        val remoteState = menu.handleWorkspaceAction(action, path) ?: return
        ServerNetworking.sendToPlayer(WorkbenchWorkspaceClientMessage(containerId, remoteState), player)

        // Eagerly open a CRDT session for whichever document the workspace response surfaced —
        // for READ that's the requested path, for LIST it's whatever document the menu had
        // open previously (e.g. on screen re-open). Without this the client would render the
        // disk-loaded text from `remoteState.document` (stale relative to peers' in-memory
        // edits) and would not appear in the per-path subscriber set, so live ops from peers
        // never reach it. Triggering the session here also broadcasts our presence so other
        // viewers' file trees light up the per-path collaborator counter immediately.
        val sessionPath =
            when {
                action == Action.READ && path.isNotEmpty() -> path
                else -> remoteState.document?.path
            }
        if (!sessionPath.isNullOrEmpty()) {
            menu.openWorkbenchSession(sessionPath)?.let { snapshot ->
                ServerNetworking.sendToPlayer(snapshot, player)
            }
        }

        // Re-send the current presence list to the requester. The synchronous broadcast that
        // [ServerWorkbench.attachMenu] fires during menu construction races with the client's
        // containerMenu swap and is silently dropped when it loses; piggybacking on the
        // workspace response guarantees the freshly opened screen sees who else is editing
        // before any peer needs to type.
        menu.resendPresenceToOwner()
    }

    override fun type(): MessageType<WorkbenchWorkspaceServerMessage> = NetworkMessages.WORKBENCH_WORKSPACE_REQUEST

    enum class Action {
        LIST,
        READ,
        RUN,
        REBOOT,
        ATTACH_TERMINAL,
    }
}
