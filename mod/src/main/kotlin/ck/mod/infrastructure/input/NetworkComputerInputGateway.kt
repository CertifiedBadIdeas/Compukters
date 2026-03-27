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
package ck.mod.infrastructure.input

import ck.mod.application.input.ComputerControlAction
import ck.mod.application.input.ComputerInputGateway
import ck.mod.application.input.KeyInputEvent
import ck.mod.application.input.MouseInputEvent
import ck.mod.application.input.PasteInputEvent
import ck.mod.network.ClientNetworking
import ck.mod.network.server.ComputerActionServerMessage
import ck.mod.network.server.KeyEventServerMessage
import ck.mod.network.server.MouseEventServerMessage
import ck.mod.network.server.PasteEventComputerMessage
import net.minecraft.world.inventory.AbstractContainerMenu

class NetworkComputerInputGateway(
    private val menu: AbstractContainerMenu,
) : ComputerInputGateway {
    override fun sendControl(action: ComputerControlAction) {
        ClientNetworking.sendToServer(ComputerActionServerMessage(menu, action.toWireAction()))
    }

    override fun sendKey(event: KeyInputEvent) {
        ClientNetworking.sendToServer(
            when (event) {
                is KeyInputEvent.Down ->
                    KeyEventServerMessage(
                        menu,
                        if (event.repeat) KeyEventServerMessage.Action.REPEAT else KeyEventServerMessage.Action.DOWN,
                        event.key,
                    )

                is KeyInputEvent.Up -> KeyEventServerMessage(menu, KeyEventServerMessage.Action.UP, event.key)
                is KeyInputEvent.Character -> KeyEventServerMessage(menu, KeyEventServerMessage.Action.CHAR, event.value.toInt())
            },
        )
    }

    override fun sendMouse(event: MouseInputEvent) {
        ClientNetworking.sendToServer(
            when (event) {
                is MouseInputEvent.Click ->
                    MouseEventServerMessage(menu, MouseEventServerMessage.Action.CLICK, event.button, event.x, event.y)

                is MouseInputEvent.Up ->
                    MouseEventServerMessage(menu, MouseEventServerMessage.Action.UP, event.button, event.x, event.y)

                is MouseInputEvent.Drag ->
                    MouseEventServerMessage(menu, MouseEventServerMessage.Action.DRAG, event.button, event.x, event.y)

                is MouseInputEvent.Scroll ->
                    MouseEventServerMessage(menu, MouseEventServerMessage.Action.SCROLL, event.direction, event.x, event.y)
            },
        )
    }

    override fun sendPaste(event: PasteInputEvent) {
        ClientNetworking.sendToServer(PasteEventComputerMessage(menu, event.contents))
    }
}

private fun ComputerControlAction.toWireAction(): ComputerActionServerMessage.Action =
    when (this) {
        ComputerControlAction.TERMINATE -> ComputerActionServerMessage.Action.TERMINATE
        ComputerControlAction.TURN_ON -> ComputerActionServerMessage.Action.TURN_ON
        ComputerControlAction.SHUTDOWN -> ComputerActionServerMessage.Action.SHUTDOWN
        ComputerControlAction.REBOOT -> ComputerActionServerMessage.Action.REBOOT
    }
