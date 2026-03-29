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

import ck.mod.menu.ServerInputHandler

/**
 * Dispatch a unified [InputEvent] to the appropriate [ServerInputHandler] method.
 */
fun ServerInputHandler.accept(event: InputEvent) {
    when (event) {
        is ControlInputEvent -> when (event.action) {
            ComputerControlAction.TERMINATE -> terminate()
            ComputerControlAction.TURN_ON -> turnOn()
            ComputerControlAction.SHUTDOWN -> shutdown()
            ComputerControlAction.REBOOT -> reboot()
        }
        is KeyInputEvent.Down -> keyDown(event.key, event.repeat)
        is KeyInputEvent.Up -> keyUp(event.key)
        is KeyInputEvent.Character -> charTyped(event.value)
        is MouseInputEvent.Click -> mouseClick(event.button, event.x, event.y)
        is MouseInputEvent.Up -> mouseUp(event.button, event.x, event.y)
        is MouseInputEvent.Drag -> mouseDrag(event.button, event.x, event.y)
        is MouseInputEvent.Scroll -> mouseScroll(event.direction, event.x, event.y)
        is PasteInputEvent -> paste(event.contents)
    }
}
