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
import ru.lazyhat.compukterkraft.common.workbench.network.readOps
import ru.lazyhat.compukterkraft.common.workbench.network.writeOps
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op

/**
 * Server → client: ack for the local site's last applied clock plus any concurrent ops from
 * other clients editing the same path.
 *
 * `ackedClock` lets `OpOutbox.onAck` clear in-flight work and snap status back to Idle.
 * `ops` carries remote edits the client hasn't seen — empty in Phase 1 (single editor) but the
 * field is on the wire from day one to avoid a protocol bump in Phase 2.
 *
 * Client-side handler is wired in Task 8.
 */
class WorkbenchOpsClientMessage : NetworkMessage<ClientNetworkContext> {
    val containerId: Int
    val path: String
    val ops: List<Op>
    val ackedClock: Int

    constructor(containerId: Int, path: String, ops: List<Op>, ackedClock: Int) {
        this.containerId = containerId
        this.path = path
        this.ops = ops
        this.ackedClock = ackedClock
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        path = buf.readUtf()
        ops = buf.readOps()
        ackedClock = buf.readVarInt()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeUtf(path)
        buf.writeOps(ops)
        buf.writeVarInt(ackedClock)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleWorkbenchOps(containerId, path, ops, ackedClock)
    }

    override fun type(): MessageType<WorkbenchOpsClientMessage> = NetworkMessages.WORKBENCH_OPS

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkbenchOpsClientMessage) return false
        return containerId == other.containerId &&
            path == other.path &&
            ops == other.ops &&
            ackedClock == other.ackedClock
    }

    override fun hashCode(): Int = (((containerId * 31) + path.hashCode()) * 31 + ops.hashCode()) * 31 + ackedClock

    override fun toString(): String = "WorkbenchOpsClientMessage(containerId=$containerId, path='$path', ops=$ops, ackedClock=$ackedClock)"
}
