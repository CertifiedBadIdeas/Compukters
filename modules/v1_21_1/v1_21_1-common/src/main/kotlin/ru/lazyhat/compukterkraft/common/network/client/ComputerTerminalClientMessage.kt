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
package ru.lazyhat.compukterkraft.common.network.client

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.gui.TerminalState
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * Server → client message carrying a terminal screen snapshot.
 */
class ComputerTerminalClientMessage : NetworkMessage<ClientNetworkContext> {
    private val containerId: Int
    private val terminalState: TerminalState

    constructor(menu: AbstractContainerMenu, snapshot: ScreenBufferSnapshot) {
        containerId = menu.containerId
        terminalState = TerminalState(snapshot)
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        terminalState = TerminalState(buf)
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        terminalState.write(buf)
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleComputerTerminal(containerId, terminalState.toSnapshot())
    }

    override fun type(): MessageType<ComputerTerminalClientMessage> = NetworkMessages.COMPUTER_TERMINAL
}
