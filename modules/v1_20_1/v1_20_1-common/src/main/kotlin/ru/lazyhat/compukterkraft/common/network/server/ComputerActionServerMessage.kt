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
package ru.lazyhat.compukterkraft.common.network.server

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.core.application.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.application.input.ControlInputEvent

class ComputerActionServerMessage : ComputerServerMessage {
    private val action: Action

    constructor(menu: AbstractContainerMenu, action: Action) : super(menu) {
        this.action = action
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        action = buf.readEnum(Action::class.java)
    }

    override fun write(buf: FriendlyByteBuf) {
        super.write(buf)
        buf.writeEnum(action)
    }

    override fun handle(
        context: ServerNetworkContext,
        container: ComputerMenu,
    ) {
        container.serverSide.input.accept(ControlInputEvent(action.toDomainAction()))
    }

    override fun type(): MessageType<ComputerActionServerMessage> = NetworkMessages.COMPUTER_ACTION

    enum class Action {
        TERMINATE,
        TURN_ON,
        SHUTDOWN,
        REBOOT,
    }
}

private fun ComputerActionServerMessage.Action.toDomainAction(): ComputerControlAction =
    when (this) {
        ComputerActionServerMessage.Action.TERMINATE -> ComputerControlAction.TERMINATE
        ComputerActionServerMessage.Action.TURN_ON -> ComputerControlAction.TURN_ON
        ComputerActionServerMessage.Action.SHUTDOWN -> ComputerControlAction.SHUTDOWN
        ComputerActionServerMessage.Action.REBOOT -> ComputerControlAction.REBOOT
    }
