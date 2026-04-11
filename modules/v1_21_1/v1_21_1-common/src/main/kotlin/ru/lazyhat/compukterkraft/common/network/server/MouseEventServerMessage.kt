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
import ru.lazyhat.compukterkraft.core.application.input.MouseInputEvent

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
        container.serverSide.input.accept(type.toDomainEvent(arg, x, y))
    }

    public override fun type(): MessageType<MouseEventServerMessage> = NetworkMessages.MOUSE_EVENT

    enum class Action {
        CLICK,
        DRAG,
        UP,
        SCROLL,
    }
}

private fun MouseEventServerMessage.Action.toDomainEvent(
    arg: Int,
    x: Int,
    y: Int,
): MouseInputEvent =
    when (this) {
        MouseEventServerMessage.Action.CLICK -> MouseInputEvent.Click(arg, x, y)
        MouseEventServerMessage.Action.DRAG -> MouseInputEvent.Drag(arg, x, y)
        MouseEventServerMessage.Action.UP -> MouseInputEvent.Up(arg, x, y)
        MouseEventServerMessage.Action.SCROLL -> MouseInputEvent.Scroll(arg, x, y)
    }
