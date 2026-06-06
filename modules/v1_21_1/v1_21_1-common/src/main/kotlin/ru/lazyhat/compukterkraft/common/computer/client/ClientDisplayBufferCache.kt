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

package ru.lazyhat.compukterkraft.common.computer.client

class ClientDisplayBufferCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Key(
        val computerId: Int,
        val displayId: Int,
        val width: Int,
        val height: Int,
    )

    private val buffers =
        object : LinkedHashMap<Key, ClientDisplayBuffer>(maxEntries, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ClientDisplayBuffer>) = size > maxEntries
        }

    @Synchronized
    fun getOrCreate(
        computerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ): ClientDisplayBuffer =
        buffers.getOrPut(Key(computerId, displayId, width, height)) {
            ClientDisplayBuffer(displayId, width, height)
        }

    @Synchronized
    fun remove(
        computerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        buffers.remove(Key(computerId, displayId, width, height))
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 32
        const val LOAD_FACTOR = 0.75f
    }
}

object ClientDisplayBuffers {
    private val cache = ClientDisplayBufferCache()

    fun getOrCreate(
        computerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ): ClientDisplayBuffer =
        cache.getOrCreate(
            computerId = computerId,
            displayId = displayId,
            width = width,
            height = height,
        )

    fun remove(
        computerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        cache.remove(
            computerId = computerId,
            displayId = displayId,
            width = width,
            height = height,
        )
    }
}
