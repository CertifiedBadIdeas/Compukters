// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import ru.lazyhat.compuktercraft.network.MessageType
import ru.lazyhat.compuktercraft.network.NetworkMessage
import ru.lazyhat.compuktercraft.network.NetworkMessages
import ru.lazyhat.compuktercraft.network.text.TableBuilder

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

    public override fun write(buf: FriendlyByteBuf) {
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

    public override fun handle(context: ClientNetworkContext) {
        context.handleChatTable(table)
    }

    public override fun type(): MessageType<ChatTableClientMessage> = NetworkMessages.CHAT_TABLE

    companion object {
        private const val MAX_LEN = 16
    }
}
