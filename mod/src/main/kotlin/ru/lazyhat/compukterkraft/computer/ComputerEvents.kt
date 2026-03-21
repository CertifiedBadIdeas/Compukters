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
package ru.lazyhat.compukterkraft.computer

import java.nio.ByteBuffer

/**
 * Built-in events that can be queued on a computer.
 */
object ComputerEvents {
    fun keyDown(
        receiver: Receiver,
        key: Int,
        repeat: Boolean,
    ) {
        receiver.queueEvent("key", arrayOf(key, repeat))
    }

    fun keyUp(
        receiver: Receiver,
        key: Int,
    ) {
        receiver.queueEvent("key_up", arrayOf(key))
    }

    /**
     * Type a character on the computer.
     *
     * @param receiver The computer to queue the event on.
     * @param chr      The character to type.
     * @see StringUtil.isTypableChar
     */
    fun charTyped(
        receiver: Receiver,
        chr: Byte,
    ) {
        receiver.queueEvent("char", arrayOf(byteArrayOf(chr)))
    }

    /**
     * Paste a string.
     *
     * @param receiver The computer to queue the event on.
     * @param contents The string to paste.
     * @see StringUtil.getClipboardString
     */
    fun paste(
        receiver: Receiver,
        contents: ByteBuffer,
    ) {
        receiver.queueEvent("paste", arrayOf(contents))
    }

    fun mouseClick(
        receiver: Receiver,
        button: Int,
        x: Int,
        y: Int,
    ) {
        receiver.queueEvent("mouse_click", arrayOf(button, x, y))
    }

    fun mouseUp(
        receiver: Receiver,
        button: Int,
        x: Int,
        y: Int,
    ) {
        receiver.queueEvent("mouse_up", arrayOf(button, x, y))
    }

    fun mouseDrag(
        receiver: Receiver,
        button: Int,
        x: Int,
        y: Int,
    ) {
        receiver.queueEvent("mouse_drag", arrayOf(button, x, y))
    }

    fun mouseScroll(
        receiver: Receiver,
        direction: Int,
        x: Int,
        y: Int,
    ) {
        receiver.queueEvent("mouse_scroll", arrayOf(direction, x, y))
    }

    /**
     * An object that can receive computer events.
     */
    fun interface Receiver {
        fun queueEvent(
            event: String,
            arguments: Array<Any>,
        )

        fun queueEvent(event: String) = queueEvent(event, emptyArray())
    }
}
