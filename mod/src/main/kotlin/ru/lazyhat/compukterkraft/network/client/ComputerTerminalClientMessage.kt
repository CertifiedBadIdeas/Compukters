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
package ru.lazyhat.compukterkraft.network.client

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.gui.TerminalState
import ru.lazyhat.compukterkraft.network.MessageType
import ru.lazyhat.compukterkraft.network.NetworkMessage
import ru.lazyhat.compukterkraft.network.NetworkMessages

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

    public override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        terminal.write(buf)
    }

    public override fun handle(context: ClientNetworkContext) {
        context.handleComputerTerminal(containerId, terminal)
    }

    public override fun type(): MessageType<ComputerTerminalClientMessage> = NetworkMessages.COMPUTER_TERMINAL
}
