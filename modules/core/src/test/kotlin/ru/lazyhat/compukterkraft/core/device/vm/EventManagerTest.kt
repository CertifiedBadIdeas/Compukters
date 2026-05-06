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

package ru.lazyhat.compukterkraft.core.device.vm

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventManagerTest {
    @Test
    fun queuedCountDoesNotExceedCapacityWhenOldEventsAreDropped() =
        runBlocking {
            val manager = EventManager(maxQueueSize = 2)

            repeat(5) { index ->
                assertTrue(manager.enqueueEvent(VmEvent("event-$index")))
            }

            assertEquals(2, manager.queuedCount())
            assertEquals("event-3", manager.receiveEvent().name)
            assertEquals("event-4", manager.receiveEvent().name)
            assertEquals(0, manager.queuedCount())
        }
}
