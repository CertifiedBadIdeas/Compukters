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
package ru.lazyhat.compukterkraft.common.network.text

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import ru.lazyhat.compukterkraft.common.network.client.ChatHelpers
import ru.lazyhat.compukterkraft.common.network.client.ChatTableClientMessage
import ru.lazyhat.compukterkraft.common.network.server.ServerNetworking
import ru.lazyhat.compukterkraft.common.utils.CommandUtils

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
