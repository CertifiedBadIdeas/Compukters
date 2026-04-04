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

package ck.mod.network.client

import ck.lang.runtime.ComputerWorkspaceDocument
import ck.lang.runtime.ComputerWorkspaceEntry
import ck.mod.network.MessageType
import ck.mod.network.NetworkMessage
import ck.mod.network.NetworkMessages
import net.minecraft.network.FriendlyByteBuf

class ComputerWorkspaceClientMessage : NetworkMessage<ClientNetworkContext> {
    private val containerId: Int
    private val kind: Kind
    private val entries: List<ComputerWorkspaceEntry>
    private val document: ComputerWorkspaceDocument?

    constructor(
        containerId: Int,
        entries: List<ComputerWorkspaceEntry>,
    ) {
        this.containerId = containerId
        this.kind = Kind.ENTRIES
        this.entries = entries
        this.document = null
    }

    constructor(
        containerId: Int,
        document: ComputerWorkspaceDocument?,
    ) {
        this.containerId = containerId
        this.kind = Kind.DOCUMENT
        this.entries = emptyList()
        this.document = document
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        kind = buf.readEnum(Kind::class.java)
        when (kind) {
            Kind.ENTRIES -> {
                val count = buf.readVarInt()
                entries =
                    List(count) {
                        ComputerWorkspaceEntry(
                            path = buf.readUtf(),
                            directory = buf.readBoolean(),
                            size = buf.readVarInt(),
                            version = buf.readVarLong(),
                        )
                    }
                document = null
            }

            Kind.DOCUMENT -> {
                entries = emptyList()
                document =
                    if (!buf.readBoolean()) {
                        null
                    } else {
                        ComputerWorkspaceDocument(
                            path = buf.readUtf(),
                            text = buf.readUtf(Short.MAX_VALUE.toInt()),
                            version = buf.readVarLong(),
                        )
                    }
            }
        }
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeEnum(kind)
        when (kind) {
            Kind.ENTRIES -> {
                buf.writeVarInt(entries.size)
                entries.forEach { entry ->
                    buf.writeUtf(entry.path)
                    buf.writeBoolean(entry.directory)
                    buf.writeVarInt(entry.size)
                    buf.writeVarLong(entry.version)
                }
            }

            Kind.DOCUMENT -> {
                buf.writeBoolean(document != null)
                document?.let {
                    buf.writeUtf(it.path)
                    buf.writeUtf(it.text, Short.MAX_VALUE.toInt())
                    buf.writeVarLong(it.version)
                }
            }
        }
    }

    override fun handle(context: ClientNetworkContext) {
        when (kind) {
            Kind.ENTRIES -> context.handleComputerWorkspaceEntries(containerId, entries)
            Kind.DOCUMENT -> context.handleComputerWorkspaceDocument(containerId, document)
        }
    }

    override fun type(): MessageType<ComputerWorkspaceClientMessage> = NetworkMessages.COMPUTER_WORKSPACE

    private enum class Kind {
        ENTRIES,
        DOCUMENT,
    }
}
