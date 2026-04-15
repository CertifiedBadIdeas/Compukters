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
package ru.lazyhat.compukterkraft.common.computer.network.server

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.core.computer.input.KeyInputEvent

class KeyEventServerMessage : ComputerServerMessage {
    private val type: Action
    private val key: Int

    constructor(menu: AbstractContainerMenu, type: Action, key: Int) : super(menu) {
        this.type = type
        this.key = key
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        type = buf.readEnum(Action::class.java)
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
        container.serverSide.input.accept(type.toDomainEvent(key))
    }

    override fun type(): MessageType<KeyEventServerMessage> = NetworkMessages.KEY_EVENT

    enum class Action {
        DOWN,
        REPEAT,
        UP,
        CHAR,
    }
}

private fun KeyEventServerMessage.Action.toDomainEvent(key: Int): KeyInputEvent =
    when (this) {
        KeyEventServerMessage.Action.DOWN -> KeyInputEvent.Down(key, repeat = false)
        KeyEventServerMessage.Action.REPEAT -> KeyInputEvent.Down(key, repeat = true)
        KeyEventServerMessage.Action.UP -> KeyInputEvent.Up(key)
        KeyEventServerMessage.Action.CHAR -> KeyInputEvent.Character(key.toByte())
    }
