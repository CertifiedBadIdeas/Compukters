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

class DisplayResizeServerMessage : ComputerServerMessage {
    private val displayId: Int
    private val width: Int
    private val height: Int

    constructor(menu: AbstractContainerMenu, displayId: Int, width: Int, height: Int) : super(menu) {
        this.displayId = displayId
        this.width = width
        this.height = height
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        displayId = buf.readVarInt()
        width = buf.readVarInt()
        height = buf.readVarInt()
    }

    override fun write(buf: FriendlyByteBuf) {
        super.write(buf)
        buf.writeVarInt(displayId)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
    }

    override fun handle(
        context: ServerNetworkContext,
        container: ComputerMenu,
    ) {
        container.serverSide.device.resizeDisplaySession(context.sender().uuid, displayId, sanitizeSize(width), sanitizeSize(height))
    }

    override fun type(): MessageType<DisplayResizeServerMessage> = NetworkMessages.DISPLAY_RESIZE

    private companion object {
        private const val MAX_ENDPOINT_SIZE = 1024

        fun sanitizeSize(value: Int): Int = value.coerceIn(1, MAX_ENDPOINT_SIZE)
    }
}