/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
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

    override fun handle(context: ServerNetworkContext, container: ComputerMenu) {
        container.serverSide.computer.resizeTerminalSession(
            playerUuid = context.sender().uuid,
            cols = cols,
            rows = rows,
        )
    }

    override fun type(): MessageType<ResizeTerminalServerMessage> = NetworkMessages.RESIZE_TERMINAL
}
