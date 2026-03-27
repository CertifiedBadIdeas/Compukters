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

import ck.mod.network.ClientNetworking
import ck.mod.network.server.ComputerActionServerMessage
import ck.mod.network.server.KeyEventServerMessage
import ck.mod.network.server.MouseEventServerMessage
import ck.mod.network.server.PasteEventComputerMessage
import net.minecraft.world.inventory.AbstractContainerMenu
import java.nio.ByteBuffer

/**
 * An [ck.mod.gui.InputHandler] for use on the client.
 *
 *
 * This queues events on the remote player's open [ComputerMenu].
 */
class ClientInputHandler(
    private val menu: AbstractContainerMenu,
) : InputHandler {
    override fun terminate() {
        ClientNetworking.sendToServer(ComputerActionServerMessage(menu, ComputerActionServerMessage.Action.TERMINATE))
    }

    override fun turnOn() {
        ClientNetworking.sendToServer(ComputerActionServerMessage(menu, ComputerActionServerMessage.Action.TURN_ON))
    }

    override fun shutdown() {
        ClientNetworking.sendToServer(ComputerActionServerMessage(menu, ComputerActionServerMessage.Action.SHUTDOWN))
    }

    override fun reboot() {
        ClientNetworking.sendToServer(ComputerActionServerMessage(menu, ComputerActionServerMessage.Action.REBOOT))
    }

    override fun keyDown(
        key: Int,
        repeat: Boolean,
    ) {
        ClientNetworking.sendToServer(
            KeyEventServerMessage(
                menu,
                if (repeat) KeyEventServerMessage.Action.REPEAT else KeyEventServerMessage.Action.DOWN,
                key,
            ),
        )
    }

    override fun keyUp(key: Int) {
        ClientNetworking.sendToServer(KeyEventServerMessage(menu, KeyEventServerMessage.Action.UP, key))
    }

    override fun charTyped(chr: Byte) {
        ClientNetworking.sendToServer(KeyEventServerMessage(menu, KeyEventServerMessage.Action.CHAR, chr.toInt()))
    }

    override fun paste(contents: ByteBuffer?) {
        ClientNetworking.sendToServer(PasteEventComputerMessage(menu, contents ?: return))
    }

    override fun mouseClick(
        button: Int,
        x: Int,
        y: Int,
    ) {
        ClientNetworking.sendToServer(MouseEventServerMessage(menu, MouseEventServerMessage.Action.CLICK, button, x, y))
    }

    override fun mouseUp(
        button: Int,
        x: Int,
        y: Int,
    ) {
        ClientNetworking.sendToServer(MouseEventServerMessage(menu, MouseEventServerMessage.Action.UP, button, x, y))
    }

    override fun mouseDrag(
        button: Int,
        x: Int,
        y: Int,
    ) {
        ClientNetworking.sendToServer(MouseEventServerMessage(menu, MouseEventServerMessage.Action.DRAG, button, x, y))
    }

    override fun mouseScroll(
        direction: Int,
        x: Int,
        y: Int,
    ) {
        ClientNetworking.sendToServer(
            MouseEventServerMessage(
                menu,
                MouseEventServerMessage.Action.SCROLL,
                direction,
                x,
                y,
            ),
        )
    }
}
