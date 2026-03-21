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
package ru.lazyhat.compukterkraft.gui

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.utils.readUByteArray
import ru.lazyhat.compukterkraft.utils.writeUByteArray

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
