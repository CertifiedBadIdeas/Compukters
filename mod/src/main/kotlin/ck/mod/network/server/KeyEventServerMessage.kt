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
package ck.mod.network.server

import ck.mod.menu.ComputerMenu
import ck.mod.network.MessageType
import ck.mod.network.NetworkMessages
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu

class KeyEventServerMessage : ComputerServerMessage {
    private val type: Action
    private val key: Int

    constructor(menu: AbstractContainerMenu, type: Action, key: Int) : super(menu) {
        this.type = type
        this.key = key
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        type = buf.readEnum<Action>(Action::class.java)
        key = buf.readVarInt()
    }

    override fun write(buf: FriendlyByteBuf) {
        super.write(buf)
        buf.writeEnum(type)
        buf.writeVarInt(key)
    }

    override fun handle(
        context: ServerNetworkContext,
        container: ComputerMenu,
    ) {
        val input = container.getInputPublic()
        when (type) {
            Action.UP -> input.keyUp(key)
            Action.DOWN -> input.keyDown(key, false)
            Action.REPEAT -> input.keyDown(key, true)
            Action.CHAR -> input.charTyped(key.toByte())
        }
    }

    public override fun type(): MessageType<KeyEventServerMessage> = NetworkMessages.KEY_EVENT

    enum class Action {
        DOWN,
        REPEAT,
        UP,
        CHAR,
    }
}
