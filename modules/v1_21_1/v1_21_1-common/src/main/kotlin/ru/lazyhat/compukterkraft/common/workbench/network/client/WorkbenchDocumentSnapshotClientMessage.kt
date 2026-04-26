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
import ru.lazyhat.compukterkraft.common.workbench.network.readRuns
import ru.lazyhat.compukterkraft.common.workbench.network.readVersionVector
import ru.lazyhat.compukterkraft.common.workbench.network.writeRuns
import ru.lazyhat.compukterkraft.common.workbench.network.writeVersionVector
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.TextRun

/**
 * Server → client: full document snapshot delivered when a workbench session opens (or
 * re-opens after a desync). The client rebuilds its [ClientCrdtReplica] from the runs and uses
 * [versionVector] to skip ops it has already authored before reconnecting.
 *
 * Client-side handler is wired in Task 8.
 */
class WorkbenchDocumentSnapshotClientMessage : NetworkMessage<ClientNetworkContext> {
    val containerId: Int
    val path: String
    val runs: List<TextRun>
    val versionVector: Map<SiteId, Int>

    constructor(
        containerId: Int,
        path: String,
        runs: List<TextRun>,
        versionVector: Map<SiteId, Int>,
    ) {
        this.containerId = containerId
        this.path = path
        this.runs = runs
        this.versionVector = versionVector
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        path = buf.readUtf()
        runs = buf.readRuns()
        versionVector = buf.readVersionVector()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeUtf(path)
        buf.writeRuns(runs)
        buf.writeVersionVector(versionVector)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleWorkbenchDocumentSnapshot(containerId, path, runs, versionVector)
    }

    override fun type(): MessageType<WorkbenchDocumentSnapshotClientMessage> = NetworkMessages.WORKBENCH_DOCUMENT_SNAPSHOT

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkbenchDocumentSnapshotClientMessage) return false
        return containerId == other.containerId &&
            path == other.path &&
            runs == other.runs &&
            versionVector == other.versionVector
    }

    override fun hashCode(): Int = (((containerId * 31) + path.hashCode()) * 31 + runs.hashCode()) * 31 + versionVector.hashCode()

    override fun toString(): String =
        "WorkbenchDocumentSnapshotClientMessage(containerId=$containerId, path='$path', " +
            "runs=$runs, versionVector=$versionVector)"
}
