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

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import java.util.concurrent.atomic.AtomicInteger

class EventManager(
    private val maxQueueSize: Int,
) {
    private val eventQueue =
        Channel<VmEvent>(
            capacity = maxQueueSize,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val queuedEvents = AtomicInteger()
    private val deferredEvents = ArrayDeque<VmEvent>()

    fun enqueueEvent(event: VmEvent): Boolean =
        eventQueue.trySend(event).isSuccess.also { accepted ->
            if (accepted) {
                queuedEvents.updateAndGet { current -> (current + 1).coerceAtMost(maxQueueSize) }
            }
        }

    suspend fun receiveEvent(): VmEvent {
        val queued = deferredEvents.removeFirstOrNull()
        if (queued != null) {
            return queued
        }
        val event = eventQueue.receive()
        queuedEvents.decrementAndGet()
        return event
    }

    fun tryReceiveEvent(): VmEvent? {
        val deferred = deferredEvents.removeFirstOrNull()
        if (deferred != null) {
            return deferred
        }
        val event = eventQueue.tryReceive().getOrNull() ?: return null
        queuedEvents.decrementAndGet()
        return event
    }

    fun deferEvent(event: VmEvent) {
        deferredEvents.addLast(event)
    }

    fun queuedCount(): Int = queuedEvents.get()
}
