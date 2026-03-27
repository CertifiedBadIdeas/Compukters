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
package ck.mod.network.client

import ck.mod.gui.TerminalState
import ck.mod.network.MessageType
import ck.mod.network.NetworkMessage
import ck.mod.network.NetworkMessages
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu

class ComputerTerminalClientMessage : NetworkMessage<ClientNetworkContext> {
    private val containerId: Int
    private val terminal: TerminalState

    constructor(menu: AbstractContainerMenu, terminal: TerminalState) {
        containerId = menu.containerId
        this.terminal = terminal
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        terminal = TerminalState(buf)
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        terminal.write(buf)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleComputerTerminal(containerId, terminal)
    }

    override fun type(): MessageType<ComputerTerminalClientMessage> = NetworkMessages.COMPUTER_TERMINAL
}
