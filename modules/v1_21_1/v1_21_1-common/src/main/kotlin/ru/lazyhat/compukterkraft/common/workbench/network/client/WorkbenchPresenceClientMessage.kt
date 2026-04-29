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
import ru.lazyhat.compukterkraft.common.workbench.network.readPresences
import ru.lazyhat.compukterkraft.common.workbench.network.writePresences
import ru.lazyhat.compukterkraft.core.computer.workbench.EditorPresence

/**
 * Server → client: full snapshot of every collaborator currently editing on this workbench.
 *
 * Sent on attach/detach and on path changes. Snapshot rather than delta keeps the wire shape
 * trivial; the list is small (one entry per open menu) and changes are infrequent compared to
 * cursor moves. Cursor updates ride a separate, leaner channel — see [WorkbenchCursorClientMessage].
 */
class WorkbenchPresenceClientMessage : NetworkMessage<ClientNetworkContext> {
    val containerId: Int
    val presences: List<EditorPresence>

    constructor(containerId: Int, presences: List<EditorPresence>) {
        this.containerId = containerId
        this.presences = presences
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        presences = buf.readPresences()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writePresences(presences)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleWorkbenchPresence(containerId, presences)
    }

    override fun type(): MessageType<WorkbenchPresenceClientMessage> = NetworkMessages.WORKBENCH_PRESENCE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkbenchPresenceClientMessage) return false
        return containerId == other.containerId && presences == other.presences
    }

    override fun hashCode(): Int = containerId * 31 + presences.hashCode()

    override fun toString(): String = "WorkbenchPresenceClientMessage(containerId=$containerId, presences=$presences)"
}
