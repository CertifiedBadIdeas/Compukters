// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.server

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compuktercraft.menu.ComputerMenu
import ru.lazyhat.compuktercraft.network.MessageType
import ru.lazyhat.compuktercraft.network.NetworkMessages

class ComputerActionServerMessage : ComputerServerMessage {
    private val action: Action

    constructor(menu: AbstractContainerMenu, action: Action) : super(menu) {
        this.action = action
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        action = buf.readEnum<Action>(Action::class.java)
    }

    override fun write(buf: FriendlyByteBuf) {
        super.write(buf)
        buf.writeEnum(action)
    }

    override fun handle(
        context: ServerNetworkContext,
        container: ComputerMenu,
    ) {
        when (action) {
            Action.TERMINATE -> container.getInputPublic().terminate()
            Action.TURN_ON -> container.getInputPublic().turnOn()
            Action.REBOOT -> container.getInputPublic().reboot()
            Action.SHUTDOWN -> container.getInputPublic().shutdown()
        }
    }

    public override fun type(): MessageType<ComputerActionServerMessage> = NetworkMessages.COMPUTER_ACTION

    enum class Action {
        TERMINATE,
        TURN_ON,
        SHUTDOWN,
        REBOOT,
    }
}
