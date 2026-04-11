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
package ru.lazyhat.compukterkraft.common.gui

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.utils.readUByteArray
import ru.lazyhat.compukterkraft.common.utils.writeUByteArray
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * Network-serializable snapshot of a terminal screen.
 *
 * Bridges between [ScreenBufferSnapshot] (the runtime representation)
 * and [FriendlyByteBuf] (the Minecraft network format).
 */
@OptIn(ExperimentalUnsignedTypes::class)
class TerminalState {
    val width: Int
    val height: Int
    val colour: Boolean
    val cursorX: Int
    val cursorY: Int
    val cursorBlink: Boolean
    val currentFg: Int
    val currentBg: Int
    private val contents: UByteArray

    constructor(snapshot: ScreenBufferSnapshot) {
        width = snapshot.width
        height = snapshot.height
        colour = snapshot.colour
        cursorX = snapshot.cursorX
        cursorY = snapshot.cursorY
        cursorBlink = snapshot.cursorBlink
        currentFg = snapshot.currentFg
        currentBg = snapshot.currentBg
        // Pack: for each row — chars then (bg<<4|fg) pairs
        val packed = UByteArray(width * height * 2)
        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                packed[idx++] = (snapshot.charAt(x, y).code and 0xFF).toUByte()
            }
            for (x in 0 until width) {
                packed[idx++] = ((snapshot.bgAt(x, y) shl 4) or snapshot.fgAt(x, y)).toUByte()
            }
        }
        contents = packed
    }

    constructor(buf: FriendlyByteBuf) {
        colour = buf.readBoolean()
        width = buf.readVarInt()
        height = buf.readVarInt()
        cursorX = buf.readVarInt()
        cursorY = buf.readVarInt()
        cursorBlink = buf.readBoolean()
        val cursorColour = buf.readByte()
        currentBg = (cursorColour.toInt() shr 4) and 0xF
        currentFg = cursorColour.toInt() and 0xF
        contents = buf.readUByteArray()
    }

    fun write(buf: FriendlyByteBuf) {
        buf.writeBoolean(colour)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
        buf.writeVarInt(cursorX)
        buf.writeVarInt(cursorY)
        buf.writeBoolean(cursorBlink)
        buf.writeByte(currentBg shl 4 or currentFg)
        buf.writeUByteArray(contents)
    }

    /**
     * Reconstruct a [ScreenBufferSnapshot] from the network data.
     */
    fun toSnapshot(): ScreenBufferSnapshot {
        val chars = CharArray(width * height)
        val fgColours = ByteArray(width * height)
        val bgColours = ByteArray(width * height)
        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                chars[y * width + x] = contents[idx++].toInt().toChar()
            }
            for (x in 0 until width) {
                val packed = contents[idx++].toInt()
                bgColours[y * width + x] = ((packed shr 4) and 0xF).toByte()
                fgColours[y * width + x] = (packed and 0xF).toByte()
            }
        }
        return ScreenBufferSnapshot(
            width = width,
            height = height,
            colour = colour,
            cursorX = cursorX,
            cursorY = cursorY,
            cursorBlink = cursorBlink,
            currentFg = currentFg,
            currentBg = currentBg,
            chars = chars,
            fgColours = fgColours,
            bgColours = bgColours,
        )
    }
}
