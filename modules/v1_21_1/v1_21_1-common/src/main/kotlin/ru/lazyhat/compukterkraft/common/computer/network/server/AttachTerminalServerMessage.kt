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

/**
 * Client → server: "open a terminal session on this computer, my screen is
 * [cols] × [rows]". Sent by [ComputerTerminalScreen][ru.lazyhat.compukterkraft.common.terminal.screen.ComputerTerminalScreen]
 * when the UI opens.
 *
 * The server creates (or refreshes) a per-player consumer on the computer's
 * [ComputerStdioBroadcaster][ru.lazyhat.compukterkraft.core.computer.vm.api.ComputerStdioBroadcaster]
 * and replays the current scrollback so the client boots with a consistent view.
 */
class AttachTerminalServerMessage : ComputerServerMessage {
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
        val player = context.sender()
        container.serverSide.computer.attachTerminalSession(
            playerUuid = player.uuid,
            containerId = targetContainerId,
            cols = cols,
            rows = rows,
        )
    }

    override fun type(): MessageType<AttachTerminalServerMessage> = NetworkMessages.ATTACH_TERMINAL
}
