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
package ru.lazyhat.compukterkraft.common.network.client

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.ComponentSerialization
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.network.text.TableBuilder

class ChatTableClientMessage : NetworkMessage<ClientNetworkContext> {
    private val table: TableBuilder

    constructor(table: TableBuilder) {
        check(table.columns >= 0) { "Cannot send an empty table" }
        this.table = table
    }

    constructor(buf: FriendlyByteBuf) {
        val id: String = buf.readUtf(MAX_LEN)
        val columns: Int = buf.readVarInt()
        val table: TableBuilder
        if (buf.readBoolean()) {
            val headers = List(columns) { ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buf) }

            table = TableBuilder(id, ArrayList(headers))
        } else {
            table = TableBuilder(id)
        }

        val rows: Int = buf.readVarInt()
        for (i in 0..<rows) {
            val row = List(columns) { ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buf) }
            table.row(*row.toTypedArray())
        }

        table.additional = buf.readVarInt()
        this.table = table
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(table.id, MAX_LEN)
        buf.writeVarInt(table.columns)
        buf.writeBoolean(table.headers != null)
        if (table.headers != null) {
            for (header in table.headers) ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buf, header)
        }

        buf.writeVarInt(table.rows.size)
        for (row in table.rows) {
            for (column in row) ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buf, column)
        }

        buf.writeVarInt(table.additional)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleChatTable(table)
    }

    override fun type(): MessageType<ChatTableClientMessage> = NetworkMessages.CHAT_TABLE

    companion object {
        private const val MAX_LEN = 16
    }
}
