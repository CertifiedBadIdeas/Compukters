// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.text

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import ru.lazyhat.compuktercraft.network.client.ChatHelpers
import ru.lazyhat.compuktercraft.network.client.ChatTableClientMessage
import ru.lazyhat.compuktercraft.network.server.ServerNetworking
import ru.lazyhat.compuktercraft.utils.CommandUtils

class TableBuilder {
    /**
     * Get the unique identifier for this table type.
     *
     *
     * When showing a table within Minecraft, previous instances of this table with
     * the same ID will be removed from chat.
     *
     * @return This table's type.
     */
    val id: String

    /**
     * Get the number of columns for this table.
     *
     *
     * This will be the same as [.getHeaders]'s length if it is non-`null`,
     * otherwise the length of the first column.
     *
     * @return The number of columns.
     */
    var columns: Int = -1
        private set
    val headers: ArrayList<Component>?
    private val _rows = ArrayList<ArrayList<Component>>()

    val rows: List<List<Component>>
        get() = _rows

    var additional: Int = 0

    constructor(id: String, headers: ArrayList<Component>) {
        this.id = id
        this.headers = headers
        columns = headers.size
    }

    constructor(id: String, vararg headers: Component) {
        this.id = id
        this.headers = ArrayList(headers.toList())
        columns = headers.size
    }

    constructor(id: String) {
        this.id = id
        headers = null
    }

    constructor(id: String, vararg headers: String) {
        this.id = id
        this.headers = ArrayList<Component>(headers.size)
        columns = headers.size

        for (i in headers.indices) this.headers[i] = ChatHelpers.header(headers[i])
    }

    fun row(vararg row: Component) {
        if (columns == -1) columns = row.size
        require(row.size == columns) { "Row is the incorrect length" }
        _rows.add(ArrayList(row.toList()))
    }

    /**
     * Trim this table to a given height.
     *
     * @param height The desired height.
     */
    fun trim(height: Int) {
        if (_rows.size > height) {
            additional += _rows.size - height - 1
            _rows.subList(height - 1, _rows.size).clear()
        }
    }

    fun display(source: CommandSourceStack) {
        if (CommandUtils.isPlayer(source)) {
            trim(18)
            val player: ServerPlayer = checkNotNull(source.entity) as ServerPlayer
            ServerNetworking.sendToPlayer(ChatTableClientMessage(this), player)
        } else {
            trim(100)
            ServerTableFormatter(source).display(this)
        }
    }
}
