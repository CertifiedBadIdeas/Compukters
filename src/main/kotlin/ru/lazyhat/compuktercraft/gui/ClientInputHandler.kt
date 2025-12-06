// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.gui

import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compuktercraft.network.ClientNetworking
import ru.lazyhat.compuktercraft.network.server.ComputerActionServerMessage
import ru.lazyhat.compuktercraft.network.server.KeyEventServerMessage
import ru.lazyhat.compuktercraft.network.server.MouseEventServerMessage
import ru.lazyhat.compuktercraft.network.server.PasteEventComputerMessage
import java.nio.ByteBuffer

/**
 * An [InputHandler] for use on the client.
 *
 *
 * This queues events on the remote player's open [ComputerMenu].
 */
class ClientInputHandler(
    private val menu: AbstractContainerMenu,
) : InputHandler {
    public override fun terminate() {
        ClientNetworking.sendToServer(ComputerActionServerMessage(menu, ComputerActionServerMessage.Action.TERMINATE))
    }

    public override fun turnOn() {
        ClientNetworking.sendToServer(ComputerActionServerMessage(menu, ComputerActionServerMessage.Action.TURN_ON))
    }

    public override fun shutdown() {
        ClientNetworking.sendToServer(ComputerActionServerMessage(menu, ComputerActionServerMessage.Action.SHUTDOWN))
    }

    public override fun reboot() {
        ClientNetworking.sendToServer(ComputerActionServerMessage(menu, ComputerActionServerMessage.Action.REBOOT))
    }

    public override fun keyDown(
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

    public override fun keyUp(key: Int) {
        ClientNetworking.sendToServer(KeyEventServerMessage(menu, KeyEventServerMessage.Action.UP, key))
    }

    public override fun charTyped(chr: Byte) {
        ClientNetworking.sendToServer(KeyEventServerMessage(menu, KeyEventServerMessage.Action.CHAR, chr.toInt()))
    }

    public override fun paste(contents: ByteBuffer?) {
        ClientNetworking.sendToServer(PasteEventComputerMessage(menu, contents ?: return))
    }

    public override fun mouseClick(
        button: Int,
        x: Int,
        y: Int,
    ) {
        ClientNetworking.sendToServer(MouseEventServerMessage(menu, MouseEventServerMessage.Action.CLICK, button, x, y))
    }

    public override fun mouseUp(
        button: Int,
        x: Int,
        y: Int,
    ) {
        ClientNetworking.sendToServer(MouseEventServerMessage(menu, MouseEventServerMessage.Action.UP, button, x, y))
    }

    public override fun mouseDrag(
        button: Int,
        x: Int,
        y: Int,
    ) {
        ClientNetworking.sendToServer(MouseEventServerMessage(menu, MouseEventServerMessage.Action.DRAG, button, x, y))
    }

    public override fun mouseScroll(
        direction: Int,
        x: Int,
        y: Int,
    ) {
        ClientNetworking.sendToServer(MouseEventServerMessage(menu, MouseEventServerMessage.Action.SCROLL, direction, x, y))
    }
}
