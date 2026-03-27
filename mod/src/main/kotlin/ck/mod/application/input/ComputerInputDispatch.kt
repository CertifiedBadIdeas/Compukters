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

fun ServerInputHandler.accept(action: ComputerControlAction) {
    when (action) {
        ComputerControlAction.TERMINATE -> terminate()
        ComputerControlAction.TURN_ON -> turnOn()
        ComputerControlAction.SHUTDOWN -> shutdown()
        ComputerControlAction.REBOOT -> reboot()
    }
}

fun ServerInputHandler.accept(event: KeyInputEvent) {
    when (event) {
        is KeyInputEvent.Down -> keyDown(event.key, event.repeat)
        is KeyInputEvent.Up -> keyUp(event.key)
        is KeyInputEvent.Character -> charTyped(event.value)
    }
}

fun ServerInputHandler.accept(event: MouseInputEvent) {
    when (event) {
        is MouseInputEvent.Click -> mouseClick(event.button, event.x, event.y)
        is MouseInputEvent.Up -> mouseUp(event.button, event.x, event.y)
        is MouseInputEvent.Drag -> mouseDrag(event.button, event.x, event.y)
        is MouseInputEvent.Scroll -> mouseScroll(event.direction, event.x, event.y)
    }
}

fun ServerInputHandler.accept(event: PasteInputEvent) {
    paste(event.contents)
}
