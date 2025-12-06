// SPDX-FileCopyrightText: 2025 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.server

import io.netty.handler.codec.DecoderException
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compuktercraft.menu.ComputerMenu
import ru.lazyhat.compuktercraft.network.MessageType
import ru.lazyhat.compuktercraft.network.NetworkMessages
import ru.lazyhat.compuktercraft.utils.StringUtil
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
        container.getInputPublic().paste(text)
    }

    public override fun type(): MessageType<PasteEventComputerMessage> = NetworkMessages.PASTE_EVENT
}
