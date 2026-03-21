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
    fun keyDown(
        key: Int,
        repeat: Boolean,
    )

    fun keyUp(key: Int)

    fun charTyped(chr: Byte)

    fun paste(contents: ByteBuffer?)

    fun mouseClick(
        button: Int,
        x: Int,
        y: Int,
    )

    fun mouseUp(
        button: Int,
        x: Int,
        y: Int,
    )

    fun mouseDrag(
        button: Int,
        x: Int,
        y: Int,
    )

    fun mouseScroll(
        direction: Int,
        x: Int,
        y: Int,
    )

    fun terminate()

    fun shutdown()

    fun turnOn()

    fun reboot()
}
