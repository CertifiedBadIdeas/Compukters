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
package ck.mod.gui.input

import ck.mod.application.input.ComputerControlAction
import ck.mod.application.input.ComputerInputGateway
import ck.mod.application.input.KeyInputEvent
import ck.mod.application.input.MouseInputEvent
import ck.mod.application.input.PasteInputEvent
import ck.mod.infrastructure.input.NetworkComputerInputGateway
import net.minecraft.world.inventory.AbstractContainerMenu
import java.nio.ByteBuffer

/**
 * An [ck.mod.gui.InputHandler] for use on the client.
 *
 * This queues events on the remote player's open [ComputerMenu].
 */
class ClientInputHandler(
    menu: AbstractContainerMenu,
) : InputHandler {
    private val gateway: ComputerInputGateway = NetworkComputerInputGateway(menu)

    override fun terminate() = gateway.sendControl(ComputerControlAction.TERMINATE)

    override fun turnOn() = gateway.sendControl(ComputerControlAction.TURN_ON)

    override fun shutdown() = gateway.sendControl(ComputerControlAction.SHUTDOWN)

    override fun reboot() = gateway.sendControl(ComputerControlAction.REBOOT)

    override fun keyDown(
        key: Int,
        repeat: Boolean,
    ) = gateway.sendKey(KeyInputEvent.Down(key, repeat))

    override fun keyUp(key: Int) = gateway.sendKey(KeyInputEvent.Up(key))

    override fun charTyped(chr: Byte) = gateway.sendKey(KeyInputEvent.Character(chr))

    override fun paste(contents: ByteBuffer?) {
        contents ?: return
        gateway.sendPaste(PasteInputEvent(contents))
    }

    override fun mouseClick(
        button: Int,
        x: Int,
        y: Int,
    ) = gateway.sendMouse(MouseInputEvent.Click(button, x, y))

    override fun mouseUp(
        button: Int,
        x: Int,
        y: Int,
    ) = gateway.sendMouse(MouseInputEvent.Up(button, x, y))

    override fun mouseDrag(
        button: Int,
        x: Int,
        y: Int,
    ) = gateway.sendMouse(MouseInputEvent.Drag(button, x, y))

    override fun mouseScroll(
        direction: Int,
        x: Int,
        y: Int,
    ) = gateway.sendMouse(MouseInputEvent.Scroll(direction, x, y))
}
