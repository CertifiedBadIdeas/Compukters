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

import ck.mod.network.MessageType
import ck.mod.network.NetworkMessage
import ck.mod.network.NetworkMessages
import ck.mod.network.text.TableBuilder
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component

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
            val headers = ArrayList<Component>(columns)
            for (i in 0..<columns) headers[i] = buf.readComponent()

            table = TableBuilder(id, headers)
        } else {
            table = TableBuilder(id)
        }

        val rows: Int = buf.readVarInt()
        for (i in 0..<rows) {
            val row = ArrayList<Component>(columns)
            for (j in 0..<columns) row[j] = buf.readComponent()
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
            for (header in table.headers) buf.writeComponent(header)
        }

        buf.writeVarInt(table.rows.size)
        for (row in table.rows) {
            for (column in row) buf.writeComponent(column)
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
