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
package ru.lazyhat.compukterkraft.core.device

import ru.lazyhat.compukterkraft.core.device.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.device.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.device.input.InputEvent
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.input.MouseInputEvent
import ru.lazyhat.compukterkraft.core.device.input.PasteInputEvent

/**
 * Built-in events that can be queued on a runtime device.
 */
object DeviceEvents {
    /**
     * Dispatch a unified [InputEvent] to the receiver, converting it to the appropriate
     * VM event name and arguments.
     */
    fun dispatch(
        receiver: Receiver,
        event: InputEvent,
    ) {
        when (event) {
            is ControlInputEvent -> {
                when (event.action) {
                    ComputerControlAction.TERMINATE -> {
                        receiver.queueEvent("terminate")
                    }

                    ComputerControlAction.SHUTDOWN -> {
                        receiver.queueEvent("shutdown")
                    }

                    ComputerControlAction.TURN_ON -> {
                        receiver.queueEvent("turn_on")
                    }

                    ComputerControlAction.REBOOT -> {
                        receiver.queueEvent("reboot")
                    }
                }
            }

            is KeyInputEvent.Down -> {
                receiver.queueEvent("key", arrayOf(event.key, event.repeat))
            }

            is KeyInputEvent.Up -> {
                receiver.queueEvent("key_up", arrayOf(event.key))
            }

            is KeyInputEvent.Character -> {
                receiver.queueEvent("char", arrayOf(byteArrayOf(event.value)))
            }

            is PasteInputEvent -> {
                receiver.queueEvent("paste", arrayOf(event.contents))
            }

            is MouseInputEvent.Click -> {
                receiver.queueEvent("mouse_click", arrayOf(event.button, event.x, event.y))
            }

            is MouseInputEvent.Up -> {
                receiver.queueEvent("mouse_up", arrayOf(event.button, event.x, event.y))
            }

            is MouseInputEvent.Drag -> {
                receiver.queueEvent("mouse_drag", arrayOf(event.button, event.x, event.y))
            }

            is MouseInputEvent.Scroll -> {
                receiver.queueEvent("mouse_scroll", arrayOf(event.direction, event.x, event.y))
            }
        }
    }

    /**
     * An object that can receive runtime device events.
     */
    fun interface Receiver {
        fun queueEvent(
            event: String,
            arguments: Array<Any>,
        )

        fun queueEvent(event: String) = queueEvent(event, emptyArray())
    }
}
