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

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

internal class EventPayloadStore(
    private val maxEvents: Int,
) {
    private val nextId = AtomicInteger(1)
    private val payloads = LinkedHashMap<Int, List<Any?>>()

    @Synchronized
    fun capture(arguments: List<Any?>): Pair<Int, Int> {
        val id = nextId.getAndIncrement()
        payloads[id] = arguments
        while (payloads.size > maxEvents.coerceAtLeast(1)) {
            val eldest = payloads.keys.firstOrNull() ?: break
            payloads.remove(eldest)
        }
        return id to arguments.size
    }

    @Synchronized
    fun argCount(eventId: Int): Int = payloads[eventId]?.size ?: 0

    @Synchronized
    fun argInt(
        eventId: Int,
        index: Int,
    ): Int =
        when (val value = payloads[eventId]?.getOrNull(index)) {
            is Int -> value
            is Long -> value.toInt()
            is Boolean -> if (value) 1 else 0
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }

    @Synchronized
    fun argBool(
        eventId: Int,
        index: Int,
    ): Boolean =
        when (val value = payloads[eventId]?.getOrNull(index)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }

    @Synchronized
    fun argString(
        eventId: Int,
        index: Int,
    ): String =
        when (val value = payloads[eventId]?.getOrNull(index)) {
            is String -> value
            is ByteArray -> value.toString(Charsets.UTF_8)
            is ByteBuffer -> decodeByteBuffer(value)
            is Int -> value.toString()
            is Long -> value.toString()
            is Boolean -> value.toString()
            else -> ""
        }

    private fun decodeByteBuffer(buffer: ByteBuffer): String {
        val duplicate = buffer.asReadOnlyBuffer()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}