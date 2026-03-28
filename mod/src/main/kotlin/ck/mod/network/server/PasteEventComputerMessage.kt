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

import ck.mod.application.input.PasteInputEvent
import ck.mod.application.input.accept
import ck.mod.menu.ComputerMenu
import ck.mod.network.MessageType
import ck.mod.network.NetworkMessages
import ck.mod.utils.StringUtil
import io.netty.handler.codec.DecoderException
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import java.nio.ByteBuffer

/**
 * Paste a string on a [ServerComputer].
 *
 * @see ServerInputHandler.paste
 */
class PasteEventComputerMessage : ComputerServerMessage {
    private val text: ByteBuffer

    constructor(menu: AbstractContainerMenu, text: ByteBuffer) : super(menu) {
        this.text = text
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        val length: Int = buf.readVarInt()
        if (length > StringUtil.MAX_PASTE_LENGTH) {
            throw DecoderException("ByteArray with size " + length + " is bigger than allowed " + StringUtil.MAX_PASTE_LENGTH)
        }

        val text = ByteArray(length)
        buf.readBytes(text)
        this.text = ByteBuffer.wrap(text)
    }

    override fun write(buf: FriendlyByteBuf) {
        super.write(buf)
        buf.writeVarInt(text.remaining())
        buf.writeBytes(text.duplicate())
    }

    override fun handle(
        context: ServerNetworkContext,
        container: ComputerMenu,
    ) {
        container.serverSide.input.accept(PasteInputEvent(text))
    }

    public override fun type(): MessageType<PasteEventComputerMessage> = NetworkMessages.PASTE_EVENT
}
