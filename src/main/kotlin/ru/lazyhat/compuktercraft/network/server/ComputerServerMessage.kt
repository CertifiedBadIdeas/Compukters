// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.server

import com.google.errorprone.annotations.OverridingMethodsMustInvokeSuper
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compuktercraft.menu.ComputerMenu
import ru.lazyhat.compuktercraft.network.NetworkMessage

/**
 * A packet, which performs an action on the currently open [ComputerMenu].
 */
abstract class ComputerServerMessage : NetworkMessage<ServerNetworkContext> {
    private val containerId: Int

    protected constructor(menu: AbstractContainerMenu) {
        containerId = menu.containerId
    }

    constructor(buffer: FriendlyByteBuf) {
        containerId = buffer.readVarInt()
    }

    @OverridingMethodsMustInvokeSuper
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
