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

/**
 * Client → server: "open a terminal session on this computer, my screen is
 * [cols] × [rows]". Sent by [ComputerTerminalScreen][ru.lazyhat.compukterkraft.common.computer.screen.ComputerTerminalScreen]
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

    override fun handle(context: ServerNetworkContext, container: ComputerMenu) {
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
