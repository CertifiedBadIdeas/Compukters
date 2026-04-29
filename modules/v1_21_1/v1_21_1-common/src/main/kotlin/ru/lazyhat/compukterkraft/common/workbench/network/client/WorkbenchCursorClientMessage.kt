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
import ru.lazyhat.compukterkraft.common.workbench.network.readNullableCursorAnchor
import ru.lazyhat.compukterkraft.common.workbench.network.readSiteId
import ru.lazyhat.compukterkraft.common.workbench.network.writeNullableCursorAnchor
import ru.lazyhat.compukterkraft.common.workbench.network.writeSiteId
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.CursorAnchor
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId

/**
 * Server → client: a single peer's caret moved on [path]. `cursor == null` clears the
 * displayed caret (peer left this file). The recipient looks up the peer in its presence list
 * to colorize the rendered caret.
 *
 * Lean channel — [path] is included so the client can ignore updates for files it isn't
 * currently viewing without dropping the broader presence set.
 */
class WorkbenchCursorClientMessage : NetworkMessage<ClientNetworkContext> {
    val containerId: Int
    val path: String
    val siteId: SiteId
    val cursor: CursorAnchor?

    constructor(containerId: Int, path: String, siteId: SiteId, cursor: CursorAnchor?) {
        this.containerId = containerId
        this.path = path
        this.siteId = siteId
        this.cursor = cursor
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        path = buf.readUtf()
        siteId = buf.readSiteId()
        cursor = buf.readNullableCursorAnchor()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeUtf(path)
        buf.writeSiteId(siteId)
        buf.writeNullableCursorAnchor(cursor)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleWorkbenchCursor(containerId, path, siteId, cursor)
    }

    override fun type(): MessageType<WorkbenchCursorClientMessage> = NetworkMessages.WORKBENCH_CURSOR

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkbenchCursorClientMessage) return false
        return containerId == other.containerId &&
            path == other.path &&
            siteId == other.siteId &&
            cursor == other.cursor
    }

    override fun hashCode(): Int =
        ((containerId * 31 + path.hashCode()) * 31 + siteId.hashCode()) * 31 + (cursor?.hashCode() ?: 0)

    override fun toString(): String =
        "WorkbenchCursorClientMessage(containerId=$containerId, path='$path', siteId=$siteId, cursor=$cursor)"
}
