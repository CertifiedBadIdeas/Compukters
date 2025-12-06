// SPDX-FileCopyrightText: 2020 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.gui

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compuktercraft.utils.readUByteArray
import ru.lazyhat.compuktercraft.utils.writeUByteArray

/**
 * A snapshot of a terminal's state.
 *
 *
 * This is somewhat memory inefficient (we build a buffer, only to write it elsewhere), however it means we get a
 * complete and accurate description of a terminal, which avoids a lot of complexities with resizing terminals, dirty
 * states, etc...
 */
@OptIn(ExperimentalUnsignedTypes::class)
class TerminalState {
    private val colour: Boolean
    val width: Int
    val height: Int
    val cursorX: Int
    val cursorY: Int
    val cursorBlink: Boolean
    val cursorBgColour: Int
    val cursorFgColour: Int
    val contents: UByteArray

    internal constructor(
        colour: Boolean,
        width: Int,
        height: Int,
        cursorX: Int,
        cursorY: Int,
        cursorBlink: Boolean,
        cursorFgColour: Int,
        cursorBgColour: Int,
        contents: UByteArray,
    ) {
        this.colour = colour
        this.width = width
        this.height = height
        this.cursorX = cursorX
        this.cursorY = cursorY
        this.cursorBlink = cursorBlink
        this.cursorFgColour = cursorFgColour
        this.cursorBgColour = cursorBgColour
        this.contents = contents
    }

    constructor(buf: FriendlyByteBuf) {
        colour = buf.readBoolean()
        width = buf.readVarInt()
        height = buf.readVarInt()
        cursorX = buf.readVarInt()
        cursorY = buf.readVarInt()
        cursorBlink = buf.readBoolean()

        val cursorColour = buf.readByte()
        this.cursorBgColour = (cursorColour.toInt() shr 4) and 0xF
        this.cursorFgColour = cursorColour.toInt() and 0xF

        contents = buf.readUByteArray()
    }

    fun write(buf: FriendlyByteBuf) {
        buf.writeBoolean(colour)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
        buf.writeVarInt(cursorX)
        buf.writeVarInt(cursorY)
        buf.writeBoolean(cursorBlink)
        buf.writeByte(cursorBgColour shl 4 or cursorFgColour)

        buf.writeUByteArray(contents)
    }

    fun size(): Int = contents.size

    fun apply(terminal: NetworkedTerminal) {
        terminal.read(this)
    }

    fun create(): NetworkedTerminal {
        val terminal = NetworkedTerminal(width, height, colour)
        terminal.read(this)
        return terminal
    }

    companion object {
        fun create(terminal: NetworkedTerminal): TerminalState = terminal.write()
    }
}
