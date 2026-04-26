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
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.workbench.menu.AbstractWorkbenchMenu
import ru.lazyhat.compukterkraft.common.workbench.network.readOps
import ru.lazyhat.compukterkraft.common.workbench.network.writeOps
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId

/**
 * Client → server: a batch of CRDT ops produced by the local editor for a single document.
 *
 * The server applies them via `ServerCrdtReplica` and answers with [WorkbenchOpsClientMessage]
 * carrying the per-site ack and any concurrent ops produced by other clients (Phase 2).
 *
 * `path` identifies the document inside the workbench's authoring root; `containerId` ties the
 * batch to the open menu, allowing the server to drop messages from stale menus after teleport.
 *
 * Server-side handler is wired in Task 7; this class is currently codec-only.
 */
class WorkbenchOpsServerMessage : NetworkMessage<ServerNetworkContext> {
    val containerId: Int
    val path: String
    val ops: List<Op>

    constructor(containerId: Int, path: String, ops: List<Op>) {
        this.containerId = containerId
        this.path = path
        this.ops = ops
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        path = buf.readUtf()
        ops = buf.readOps()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeUtf(path)
        buf.writeOps(ops)
    }

    override fun handle(context: ServerNetworkContext) {
        val player = context.sender()
        val menu = player.containerMenu
        if (menu.containerId != containerId || menu !is AbstractWorkbenchMenu) return

        val sender = SiteId.player(player.uuid)
        val reply = menu.handleOpsRequest(path, ops, sender) ?: return
        ServerNetworking.sendToPlayer(reply, player)
    }

    override fun type(): MessageType<WorkbenchOpsServerMessage> = NetworkMessages.WORKBENCH_OPS_REQUEST

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkbenchOpsServerMessage) return false
        return containerId == other.containerId && path == other.path && ops == other.ops
    }

    override fun hashCode(): Int = (containerId * 31 + path.hashCode()) * 31 + ops.hashCode()

    override fun toString(): String = "WorkbenchOpsServerMessage(containerId=$containerId, path='$path', ops=$ops)"
}
