/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.network

/** Server-thread port for exactly one physical peer. No routing or world access belongs here. */
interface ComputerNetworkPort {
    val connected: Boolean

    fun send(message: ByteArray)

    fun receive(): ByteArray?
}

fun interface ComputerNetworkEndpoint {
    fun connection(): ComputerNetworkPort?
}

class ComputerNetworkFailure(
    message: String,
) : IllegalStateException(message)

/** Shared per-world cap in addition to each port's message count. */
class ComputerMessageBudget(
    private val maximumBytes: Int = 1024 * 1024,
) {
    private var used = 0

    init {
        require(maximumBytes >= 0)
    }

    internal fun reserve(size: Int) {
        if (size > maximumBytes - used) throw ComputerNetworkFailure("Computer network message budget is full")
        used += size
    }

    internal fun release(size: Int) {
        used -= size
    }
}

/** A new instance is a new connection; reconnecting never revives an old waiting receive. */
class ComputerMessageLink(
    private val budget: ComputerMessageBudget = ComputerMessageBudget(),
) {
    private val queues = List(2) { ArrayDeque<ByteArray>() }
    private var active = true
    val ports: List<ComputerNetworkPort> =
        List(2) { side ->
            object : ComputerNetworkPort {
                override val connected: Boolean get() = active

                override fun send(message: ByteArray) {
                    checkActive()
                    if (message.size > MAXIMUM_MESSAGE_BYTES) throw ComputerNetworkFailure("Computer network message exceeds 4096 bytes")
                    val queue = queues[1 - side]
                    if (queue.size >= MAXIMUM_QUEUED_MESSAGES) throw ComputerNetworkFailure("Peer computer inbox is full")
                    budget.reserve(message.size)
                    queue.addLast(message.copyOf())
                }

                override fun receive(): ByteArray? {
                    checkActive()
                    return queues[side].removeFirstOrNull()?.also { budget.release(it.size) }
                }
            }
        }

    fun disconnect() {
        active = false
        queues.forEach { queue ->
            queue.forEach { budget.release(it.size) }
            queue.clear()
        }
    }

    private fun checkActive() {
        if (!active) throw ComputerNetworkFailure("Computer cable connection is unavailable")
    }

    companion object {
        const val MAXIMUM_MESSAGE_BYTES = 4096
        const val MAXIMUM_QUEUED_MESSAGES = 16
    }
}
