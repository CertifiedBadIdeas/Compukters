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
package ck.mod.network.server

import ck.mod.menu.ComputerMenu
import ck.mod.network.NetworkMessage
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * A packet, which performs an action on the currently open [ComputerMenu].
 */
abstract class ComputerServerMessage : NetworkMessage<ServerNetworkContext> {
    private val containerId: Int

    protected val targetContainerId: Int
        get() = containerId

    protected constructor(menu: AbstractContainerMenu) {
        containerId = menu.containerId
    }

    constructor(buffer: FriendlyByteBuf) {
        containerId = buffer.readVarInt()
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
    }

    override fun handle(context: ServerNetworkContext) {
        val player: Player = context.sender()
        if (player.containerMenu.containerId == containerId && player.containerMenu is ComputerMenu) {
            handle(context, player.containerMenu as ComputerMenu)
        }
    }

    protected abstract fun handle(
        context: ServerNetworkContext,
        container: ComputerMenu,
    )
}
