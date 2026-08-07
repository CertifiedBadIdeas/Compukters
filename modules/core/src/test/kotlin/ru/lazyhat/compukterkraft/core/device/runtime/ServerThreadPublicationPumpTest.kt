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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.core.device.runtime.ports.ServerThreadDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerThreadPublicationPumpTest {
    @Test
    fun schedulesOnlyOneTaskForMultipleOffers() {
        val dispatcher = RecordingServerThreadDispatcher()
        val consumed = mutableListOf<Int>()
        val pump = ServerThreadPublicationPump(dispatcher, consumed::add)

        pump.offer(1)
        pump.offer(2)
        pump.offer(3)

        assertEquals(1, dispatcher.pendingTaskCount)
        assertTrue(consumed.isEmpty())
        dispatcher.runNext()
        assertEquals(listOf(1, 2, 3), consumed)
    }

    @Test
    fun drainsOfferMadeWhileConsumerIsRunningWithoutAnotherTask() {
        val dispatcher = RecordingServerThreadDispatcher()
        val consumed = mutableListOf<Int>()
        lateinit var pump: ServerThreadPublicationPump<Int>
        pump =
            ServerThreadPublicationPump(dispatcher) { value ->
                consumed += value
                if (value == 1) pump.offer(2)
            }

        pump.offer(1)
        dispatcher.runNext()

        assertEquals(listOf(1, 2), consumed)
        assertEquals(0, dispatcher.pendingTaskCount)
    }

    @Test
    fun dispatcherFailureDoesNotLeaveDoorbellPermanentlyArmed() {
        val dispatcher = RejectFirstServerThreadDispatcher()
        val consumed = mutableListOf<Int>()
        val pump = ServerThreadPublicationPump(dispatcher, consumed::add)

        assertFailsWith<IllegalStateException> { pump.offer(1) }
        pump.offer(2)
        dispatcher.runNext()

        assertEquals(listOf(1, 2), consumed)
    }

    @Test
    fun consumerFailureDoesNotLeaveDoorbellPermanentlyArmed() {
        val dispatcher = RecordingServerThreadDispatcher()
        val consumed = mutableListOf<Int>()
        var reject = true
        val pump =
            ServerThreadPublicationPump<Int>(dispatcher) { value ->
                if (reject) {
                    reject = false
                    throw IllegalStateException("consumer rejected publication")
                }
                consumed += value
            }

        pump.offer(1)
        assertFailsWith<IllegalStateException> { dispatcher.runNext() }
        pump.offer(2)
        dispatcher.runNext()

        assertEquals(listOf(2), consumed)
    }

    private open class RecordingServerThreadDispatcher : ServerThreadDispatcher {
        private val tasks = ArrayDeque<() -> Unit>()

        val pendingTaskCount: Int
            get() = tasks.size

        override fun dispatch(task: () -> Unit) {
            tasks += task
        }

        fun runNext() {
            tasks.removeFirst().invoke()
        }
    }

    private class RejectFirstServerThreadDispatcher : RecordingServerThreadDispatcher() {
        private var reject = true

        override fun dispatch(task: () -> Unit) {
            if (reject) {
                reject = false
                throw IllegalStateException("dispatcher rejected task")
            }
            super.dispatch(task)
        }
    }
}
