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
package ck.mod.application.input

import java.nio.ByteBuffer

enum class ComputerControlAction {
    TERMINATE,
    TURN_ON,
    SHUTDOWN,
    REBOOT,
}

sealed interface KeyInputEvent {
    data class Down(
        val key: Int,
        val repeat: Boolean,
    ) : KeyInputEvent

    data class Up(
        val key: Int,
    ) : KeyInputEvent

    data class Character(
        val value: Byte,
    ) : KeyInputEvent
}

sealed interface MouseInputEvent {
    data class Click(
        val button: Int,
        val x: Int,
        val y: Int,
    ) : MouseInputEvent

    data class Up(
        val button: Int,
        val x: Int,
        val y: Int,
    ) : MouseInputEvent

    data class Drag(
        val button: Int,
        val x: Int,
        val y: Int,
    ) : MouseInputEvent

    data class Scroll(
        val direction: Int,
        val x: Int,
        val y: Int,
    ) : MouseInputEvent
}

data class PasteInputEvent(
    val contents: ByteBuffer,
)
