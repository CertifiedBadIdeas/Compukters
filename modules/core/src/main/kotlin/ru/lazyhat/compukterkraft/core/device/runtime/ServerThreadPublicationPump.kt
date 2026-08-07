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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class ServerThreadPublicationPump<T>(
    private val dispatcher: ServerThreadDispatcher,
    private val consume: (T) -> Unit,
) {
    private val ready = ConcurrentLinkedQueue<T>()
    private val scheduled = AtomicBoolean()

    fun offer(value: T) {
        ready += value
        scheduleIfIdle()
    }

    private fun scheduleIfIdle() {
        if (!scheduled.compareAndSet(false, true)) return
        try {
            dispatcher.dispatch(::drain)
        } catch (error: Throwable) {
            scheduled.set(false)
            throw error
        }
    }

    private fun drain() {
        while (true) {
            try {
                while (true) {
                    consume(ready.poll() ?: break)
                }
            } catch (error: Throwable) {
                scheduled.set(false)
                try {
                    if (ready.isNotEmpty()) scheduleIfIdle()
                } catch (dispatchError: Throwable) {
                    error.addSuppressed(dispatchError)
                }
                throw error
            }
            scheduled.set(false)
            if (ready.isEmpty() || !scheduled.compareAndSet(false, true)) return
        }
    }
}
