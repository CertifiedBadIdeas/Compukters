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
import ru.lazyhat.compukterkraft.common.workbench.menu.AbstractWorkbenchMenu
import ru.lazyhat.compukterkraft.common.workbench.network.readNullableCursorAnchor
import ru.lazyhat.compukterkraft.common.workbench.network.writeNullableCursorAnchor
import ru.lazyhat.compukterkraft.core.workbench.crdt.CursorAnchor

/**
 * Client → server: the local editor's caret moved on [path]. The server updates the player's
 * presence and broadcasts a [WorkbenchCursorClientMessage] to every other subscriber on the
 * same path. `cursor == null` removes the caret (the local user closed the file or there's no
 * meaningful anchor yet).
 */
class WorkbenchCursorServerMessage : NetworkMessage<ServerNetworkContext> {
    val containerId: Int
    val path: String
    val cursor: CursorAnchor?

    constructor(containerId: Int, path: String, cursor: CursorAnchor?) {
        this.containerId = containerId
        this.path = path
        this.cursor = cursor
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        path = buf.readUtf()
        cursor = buf.readNullableCursorAnchor()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeUtf(path)
        buf.writeNullableCursorAnchor(cursor)
    }

    override fun handle(context: ServerNetworkContext) {
        val player = context.sender()
        val menu = player.containerMenu
        if (menu.containerId != containerId || menu !is AbstractWorkbenchMenu) return
        menu.handleCursorUpdate(path, cursor)
    }

    override fun type(): MessageType<WorkbenchCursorServerMessage> = NetworkMessages.WORKBENCH_CURSOR_REQUEST

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkbenchCursorServerMessage) return false
        return containerId == other.containerId && path == other.path && cursor == other.cursor
    }

    override fun hashCode(): Int = (containerId * 31 + path.hashCode()) * 31 + (cursor?.hashCode() ?: 0)

    override fun toString(): String = "WorkbenchCursorServerMessage(containerId=$containerId, path='$path', cursor=$cursor)"
}
