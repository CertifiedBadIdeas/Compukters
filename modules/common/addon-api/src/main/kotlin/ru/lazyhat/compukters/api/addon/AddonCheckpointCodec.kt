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

package ru.lazyhat.compukters.api.addon

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded framing for host-owned resource descriptions; each owner versions its own payload. */
object AddonCheckpointCodec {
    private const val MAXIMUM_BYTES = 1024 * 1024
    private const val MAXIMUM_PARTS = 128

    fun encode(parts: List<ByteArray>): ByteArray {
        require(parts.size <= MAXIMUM_PARTS)
        var size = 8
        parts.forEach {
            require(it.size <= MAXIMUM_BYTES - size - 4) { "addon checkpoint exceeds bounds" }
            size += 4 + it.size
        }
        return ByteBuffer
            .allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(1).putInt(parts.size)
                parts.forEach { putInt(it.size).put(it) }
            }.array()
    }

    fun decode(
        encoded: ByteArray,
        expectedParts: Int,
    ): List<ByteArray> {
        require(expectedParts in 0..MAXIMUM_PARTS && encoded.size in 8..MAXIMUM_BYTES)
        val bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.int == 1 && bytes.int == expectedParts) { "incompatible addon checkpoint" }
        val parts =
            List(expectedParts) {
                require(bytes.remaining() >= 4) { "truncated addon checkpoint" }
                val size = bytes.int
                require(size >= 0 && size <= bytes.remaining()) { "invalid addon checkpoint length" }
                ByteArray(size).also(bytes::get)
            }
        require(!bytes.hasRemaining()) { "trailing addon checkpoint bytes" }
        return parts
    }
}
