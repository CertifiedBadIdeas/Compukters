// SPDX-FileCopyrightText: 2025 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.computer

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
