// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.client

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compuktercraft.gui.TerminalState
import ru.lazyhat.compuktercraft.network.MessageType
import ru.lazyhat.compuktercraft.network.NetworkMessage
import ru.lazyhat.compuktercraft.network.NetworkMessages

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
