// SPDX-FileCopyrightText: 2019 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.gui

import java.nio.ByteBuffer

/**
 * Handles user-provided input, forwarding it to a computer. This describes the "shape" of both the client-and
 * server-side input handlers.
 *
 * @see ServerInputHandler
 *
 * @see ServerComputer
 */
interface InputHandler {
	fun keyDown(key: Int, repeat: Boolean)

	fun keyUp(key: Int)

	fun charTyped(chr: Byte)

	fun paste(contents: ByteBuffer?)

	fun mouseClick(button: Int, x: Int, y: Int)

	fun mouseUp(button: Int, x: Int, y: Int)

	fun mouseDrag(button: Int, x: Int, y: Int)

	fun mouseScroll(direction: Int, x: Int, y: Int)

	fun terminate()

	fun shutdown()

	fun turnOn()

	fun reboot()
}
