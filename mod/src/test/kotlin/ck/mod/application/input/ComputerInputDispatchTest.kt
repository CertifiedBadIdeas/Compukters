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

import ck.mod.computer.ComputerEvents
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerInputDispatchTest {
    @Test
    fun dispatchesKeyAndMouseAndPasteEvents() {
        val receiver = RecordingReceiver()
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3))

        ComputerEvents.dispatch(receiver, KeyInputEvent.Down(key = 42, repeat = true))
        ComputerEvents.dispatch(receiver, KeyInputEvent.Up(key = 42))
        ComputerEvents.dispatch(receiver, KeyInputEvent.Character(7))
        ComputerEvents.dispatch(receiver, MouseInputEvent.Click(button = 0, x = 5, y = 10))
        ComputerEvents.dispatch(receiver, MouseInputEvent.Scroll(direction = -1, x = 3, y = 9))
        ComputerEvents.dispatch(receiver, PasteInputEvent(buffer))

        assertEquals(
            listOf("key", "key_up", "char", "mouse_click", "mouse_scroll", "paste"),
            receiver.events.map { it.first },
        )
        // Verify key event args
        assertEquals(listOf(42, true), receiver.events[0].second.toList())
        assertEquals(listOf(42), receiver.events[1].second.toList())
    }

    @Test
    fun dispatchesAllControlActions() {
        val receiver = RecordingReceiver()

        ComputerEvents.dispatch(receiver, ControlInputEvent(ComputerControlAction.TERMINATE))
        ComputerEvents.dispatch(receiver, ControlInputEvent(ComputerControlAction.SHUTDOWN))
        ComputerEvents.dispatch(receiver, ControlInputEvent(ComputerControlAction.TURN_ON))
        ComputerEvents.dispatch(receiver, ControlInputEvent(ComputerControlAction.REBOOT))

        assertEquals(
            listOf("terminate", "shutdown", "turn_on", "reboot"),
            receiver.events.map { it.first },
        )
    }

    private class RecordingReceiver : ComputerEvents.Receiver {
        val events = mutableListOf<Pair<String, Array<Any>>>()

        override fun queueEvent(event: String, arguments: Array<Any>) {
            events += event to arguments
        }
    }
}
