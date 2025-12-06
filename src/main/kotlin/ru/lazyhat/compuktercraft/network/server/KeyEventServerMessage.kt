// SPDX-FileCopyrightText: 2019 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.server

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compuktercraft.menu.ComputerMenu
import ru.lazyhat.compuktercraft.network.MessageType
import ru.lazyhat.compuktercraft.network.NetworkMessages

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
