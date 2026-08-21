/*
 * The Compukters Developers
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
package ru.lazyhat.compukters.core.device.input

import java.nio.ByteBuffer

/**
 * Unified sealed interface for all input events that can be sent to a computer.
 *
 * Subtypes cover keyboard input ([KeyInputEvent]), mouse input ([MouseInputEvent]),
 * clipboard paste ([PasteInputEvent]), and control actions ([ControlInputEvent]).
 */
sealed interface InputEvent

enum class ComputerControlAction {
    TERMINATE,
    TURN_ON,
    SHUTDOWN,
    REBOOT,
}

/** Control actions (turn on, shutdown, reboot, terminate). */
data class ControlInputEvent(
    val action: ComputerControlAction,
) : InputEvent

sealed interface KeyInputEvent : InputEvent {
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

sealed interface MouseInputEvent : InputEvent {
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
) : InputEvent
