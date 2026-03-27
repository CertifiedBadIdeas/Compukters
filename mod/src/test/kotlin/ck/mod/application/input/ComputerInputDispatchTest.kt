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
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerInputDispatchTest {
    @Test
    fun dispatchesControlKeyMouseAndPasteEvents() {
        val handler = RecordingServerInputHandler()
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3))

        handler.accept(ComputerControlAction.REBOOT)
        handler.accept(KeyInputEvent.Down(key = 42, repeat = true))
        handler.accept(KeyInputEvent.Character(7))
        handler.accept(MouseInputEvent.Scroll(direction = -1, x = 3, y = 9))
        handler.accept(PasteInputEvent(buffer))

        assertEquals(
            listOf(
                "reboot",
                "keyDown:42:true",
                "char:7",
                "scroll:-1:3:9",
                "paste:3",
            ),
            handler.calls,
        )
    }

    private class RecordingServerInputHandler : ServerInputHandler {
        val calls = mutableListOf<String>()

        override fun keyDown(
            key: Int,
            repeat: Boolean,
        ) {
            calls += "keyDown:$key:$repeat"
        }

        override fun keyUp(key: Int) {
            calls += "keyUp:$key"
        }

        override fun charTyped(chr: Byte) {
            calls += "char:$chr"
        }

        override fun paste(contents: ByteBuffer?) {
            calls += "paste:${contents?.remaining() ?: 0}"
        }

        override fun mouseClick(
            button: Int,
            x: Int,
            y: Int,
        ) {
            calls += "click:$button:$x:$y"
        }

        override fun mouseUp(
            button: Int,
            x: Int,
            y: Int,
        ) {
            calls += "up:$button:$x:$y"
        }

        override fun mouseDrag(
            button: Int,
            x: Int,
            y: Int,
        ) {
            calls += "drag:$button:$x:$y"
        }

        override fun mouseScroll(
            direction: Int,
            x: Int,
            y: Int,
        ) {
            calls += "scroll:$direction:$x:$y"
        }

        override fun terminate() {
            calls += "terminate"
        }

        override fun shutdown() {
            calls += "shutdown"
        }

        override fun turnOn() {
            calls += "turnOn"
        }

        override fun reboot() {
            calls += "reboot"
        }
    }
}
