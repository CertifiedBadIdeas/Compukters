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
package ru.lazyhat.compukterkraft.common.workbench.network.server

import io.netty.handler.codec.DecoderException
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext
import ru.lazyhat.compukterkraft.common.workbench.menu.AbstractWorkbenchMenu
import ru.lazyhat.compukterkraft.core.device.input.InputEvent
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.input.MouseInputEvent
import ru.lazyhat.compukterkraft.core.device.input.PasteInputEvent
import ru.lazyhat.compukterkraft.core.utils.StringUtil
import java.nio.ByteBuffer

class WorkbenchInputServerMessage : NetworkMessage<ServerNetworkContext> {
    private val containerId: Int
    private val action: Action
    private val arg: Int
    private val x: Int
    private val y: Int
    private val paste: ByteBuffer?

    constructor(
        menu: AbstractContainerMenu,
        action: Action,
        arg: Int = 0,
        x: Int = 0,
        y: Int = 0,
        paste: ByteBuffer? = null,
    ) {
        containerId = menu.containerId
        this.action = action
        this.arg = arg
        this.x = x
        this.y = y
        this.paste = paste?.duplicate()
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        action = buf.readEnum(Action::class.java)
        arg = buf.readVarInt()
        x = buf.readVarInt()
        y = buf.readVarInt()
        paste =
            if (!buf.readBoolean()) {
                null
            } else {
                val length = buf.readVarInt()
                if (length > StringUtil.MAX_PASTE_LENGTH) {
                    throw DecoderException("ByteArray with size $length is bigger than allowed ${StringUtil.MAX_PASTE_LENGTH}")
                }
                val text = ByteArray(length)
                buf.readBytes(text)
                ByteBuffer.wrap(text)
            }
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeEnum(action)
        buf.writeVarInt(arg)
        buf.writeVarInt(x)
        buf.writeVarInt(y)
        buf.writeBoolean(paste != null)
        paste?.let {
            buf.writeVarInt(it.remaining())
            buf.writeBytes(it.duplicate())
        }
    }

    override fun handle(context: ServerNetworkContext) {
        val player = context.sender()
        val menu = player.containerMenu
        if (menu.containerId != containerId || menu !is AbstractWorkbenchMenu) return
        menu.handleInputEvent(toDomainEvent())
    }

    override fun type(): MessageType<WorkbenchInputServerMessage> = NetworkMessages.WORKBENCH_INPUT

    private fun toDomainEvent(): InputEvent =
        when (action) {
            Action.KEY_DOWN -> KeyInputEvent.Down(arg, repeat = false)
            Action.KEY_REPEAT -> KeyInputEvent.Down(arg, repeat = true)
            Action.KEY_UP -> KeyInputEvent.Up(arg)
            Action.KEY_CHAR -> KeyInputEvent.Character(arg.toByte())
            Action.MOUSE_CLICK -> MouseInputEvent.Click(arg, x, y)
            Action.MOUSE_DRAG -> MouseInputEvent.Drag(arg, x, y)
            Action.MOUSE_UP -> MouseInputEvent.Up(arg, x, y)
            Action.MOUSE_SCROLL -> MouseInputEvent.Scroll(arg, x, y)
            Action.PASTE -> PasteInputEvent(requireNotNull(paste) { "Paste payload is missing" })
        }

    enum class Action {
        KEY_DOWN,
        KEY_REPEAT,
        KEY_UP,
        KEY_CHAR,
        MOUSE_CLICK,
        MOUSE_DRAG,
        MOUSE_UP,
        MOUSE_SCROLL,
        PASTE,
    }
}
