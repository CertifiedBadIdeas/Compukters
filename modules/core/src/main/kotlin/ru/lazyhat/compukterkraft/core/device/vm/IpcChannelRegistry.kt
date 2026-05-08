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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

internal class IpcChannelRegistry(
    private val maxBufferedBytesPerChannel: Int,
) {
    private val nextId = AtomicInteger(1)
    private val mutex = Mutex()
    private val channels = mutableMapOf<Int, IpcChannel>()

    suspend fun open(): Int =
        mutex.withLock {
            val id = nextId.getAndIncrement()
            channels[id] = IpcChannel()
            id
        }

    suspend fun write(
        channelId: Int,
        text: String,
    ) {
        val signal =
            mutex.withLock {
                val channel = channels[channelId] ?: throw IllegalArgumentException("Unknown IPC channel: $channelId")
                check(!channel.closed) { "IPC channel is closed: $channelId" }
                val nextBytes = (channel.buffer.toString() + text).toByteArray(Charsets.UTF_8).size
                check(nextBytes <= maxBufferedBytesPerChannel) { "IPC channel buffer limit exceeded" }
                channel.buffer.append(text)
                channel.signal
            }
        signal.trySend(Unit)
    }

    suspend fun read(channelId: Int): String {
        while (true) {
            val signal =
                mutex.withLock {
                    val channel = channels[channelId] ?: return ""
                    if (channel.buffer.isNotEmpty()) {
                        return channel.drain()
                    }
                    if (channel.closed) {
                        return ""
                    }
                    channel.signal
                }
            signal.receive()
        }
    }

    suspend fun tryRead(channelId: Int): String =
        mutex.withLock {
            val channel = channels[channelId] ?: return@withLock ""
            if (channel.buffer.isEmpty()) "" else channel.drain()
        }

    fun tryReadBlocking(channelId: Int): String = runBlocking { tryRead(channelId) }

    suspend fun readSignal(channelId: Int): Channel<Unit>? =
        mutex.withLock {
            channels[channelId]?.signal
        }

    suspend fun close(channelId: Int) {
        val signal =
            mutex.withLock {
                val channel = channels[channelId] ?: return
                channel.closed = true
                channel.signal
            }
        signal.trySend(Unit)
    }

    fun closeBlocking(channelId: Int) = runBlocking { close(channelId) }

    private class IpcChannel {
        val buffer = StringBuilder()
        val signal = Channel<Unit>(capacity = Channel.CONFLATED)
        var closed: Boolean = false

        fun drain(): String {
            val text = buffer.toString()
            buffer.clear()
            return text
        }
    }
}
