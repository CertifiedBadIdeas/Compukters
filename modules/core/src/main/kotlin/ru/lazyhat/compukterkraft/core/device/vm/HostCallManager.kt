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

import kotlinx.coroutines.CompletableDeferred
import ru.lazyhat.compukterkraft.lang.runtime.HostCall
import ru.lazyhat.compukterkraft.lang.runtime.HostResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HostCallManager(
    private val maxQueueSize: Int = Int.MAX_VALUE,
) {
    private val hostCalls = ConcurrentLinkedQueue<HostCall>()
    private val hostResponses = ConcurrentHashMap<Long, CompletableDeferred<HostResult>>()
    private val nextHostCallId = AtomicLong()
    private val queuedCalls = AtomicInteger()

    suspend fun <T> awaitHostCall(callFactory: (Long) -> HostCall): T {
        check(maxQueueSize <= 0 || queuedCalls.get() < maxQueueSize) {
            "Host call queue is full (limit=$maxQueueSize)"
        }
        val callId = nextHostCallId.incrementAndGet()
        val deferred = CompletableDeferred<HostResult>()
        hostResponses[callId] = deferred
        hostCalls.add(callFactory(callId))
        queuedCalls.incrementAndGet()
        return when (val result = deferred.await()) {
            is HostResult.Success -> result.value as T
            is HostResult.Failure -> error(result.message)
        }
    }

    fun drainHostCalls(): List<HostCall> =
        buildList {
            while (true) {
                val call = hostCalls.poll() ?: break
                queuedCalls.decrementAndGet()
                add(call)
            }
        }

    fun deliverHostResults(results: List<HostResult>) {
        for (result in results) {
            hostResponses.remove(result.id)?.complete(result)
        }
    }

    fun pendingCallsCount(): Int = queuedCalls.get()
}
