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
package ru.lazyhat.compukterkraft.common.computer.network.server

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext

/** Client → server: "my terminal window resized; bytes from now on are rendered into a [cols] × [rows] buffer". */
class ResizeTerminalServerMessage : ComputerServerMessage {
    val cols: Int
    val rows: Int

    constructor(menu: AbstractContainerMenu, cols: Int, rows: Int) : super(menu) {
        this.cols = cols
        this.rows = rows
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        cols = buf.readVarInt()
        rows = buf.readVarInt()
    }

    override fun write(buf: FriendlyByteBuf) {
        super.write(buf)
        buf.writeVarInt(cols)
        buf.writeVarInt(rows)
    }

    override fun handle(
        context: ServerNetworkContext,
        container: ComputerMenu,
    ) {
        container.serverSide.device.resizeTerminalSession(
            playerUuid = context.sender().uuid,
            cols = cols,
            rows = rows,
        )
    }

    override fun type(): MessageType<ResizeTerminalServerMessage> = NetworkMessages.RESIZE_TERMINAL
}
