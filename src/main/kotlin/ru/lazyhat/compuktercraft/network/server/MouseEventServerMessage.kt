// SPDX-FileCopyrightText: 2019 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.server

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compuktercraft.menu.ComputerMenu
import ru.lazyhat.compuktercraft.network.MessageType
import ru.lazyhat.compuktercraft.network.NetworkMessages

class MouseEventServerMessage : ComputerServerMessage {
    private val type: Action
    private val x: Int
    private val y: Int
    private val arg: Int

    constructor(menu: AbstractContainerMenu, type: Action, arg: Int, x: Int, y: Int) : super(menu) {
        this.type = type
        this.arg = arg
        this.x = x
        this.y = y
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        type = buf.readEnum<Action>(Action::class.java)
        arg = buf.readVarInt()
        x = buf.readVarInt()
        y = buf.readVarInt()
    }

    override fun write(buf: FriendlyByteBuf) {
        super.write(buf)
        buf.writeEnum(type)
        buf.writeVarInt(arg)
        buf.writeVarInt(x)
        buf.writeVarInt(y)
    }

    override fun handle(
        context: ServerNetworkContext,
        container: ComputerMenu,
    ) {
        val input = container.getInputPublic()
        when (type) {
            Action.CLICK -> input.mouseClick(arg, x, y)
            Action.DRAG -> input.mouseDrag(arg, x, y)
            Action.UP -> input.mouseUp(arg, x, y)
            Action.SCROLL -> input.mouseScroll(arg, x, y)
        }
    }

    public override fun type(): MessageType<MouseEventServerMessage> = NetworkMessages.MOUSE_EVENT

    enum class Action {
        CLICK,
        DRAG,
        UP,
        SCROLL,
    }
}
