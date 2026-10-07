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

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Little-endian, size-bounded helpers for addon-owned portable resource descriptors. */
class ResourceCheckpointWriter(
    private val maximumBytes: Int = 1024 * 1024,
) {
    private val output = ByteArrayOutputStream()

    init {
        require(maximumBytes in 1..1024 * 1024)
    }

    fun int(value: Int) {
        raw(
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array(),
        )
    }

    fun long(value: Long) {
        raw(
            ByteBuffer
                .allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value)
                .array(),
        )
    }

    fun bytes(
        value: ByteArray,
        maximum: Int,
    ) {
        require(value.size <= maximum)
        int(value.size)
        raw(value)
    }

    fun text(
        value: String,
        maximum: Int,
    ) {
        require(value.length <= maximum)
        bytes(value.encodeToByteArray(), maximum)
    }

    fun finish(): ByteArray = output.toByteArray()

    private fun raw(value: ByteArray) {
        require(value.size <= maximumBytes - output.size()) { "resource checkpoint exceeds bounds" }
        output.write(value)
    }
}

class ResourceCheckpointReader(
    encoded: ByteArray,
) {
    private val input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)

    init {
        require(encoded.size <= 1024 * 1024)
    }

    fun int(): Int {
        require(input.remaining() >= 4)
        return input.int
    }

    fun long(): Long {
        require(input.remaining() >= 8)
        return input.long
    }

    fun count(maximum: Int): Int = int().also { require(it in 0..maximum) }

    fun bytes(maximum: Int): ByteArray {
        val count = count(maximum)
        require(count <= input.remaining())
        return ByteArray(count).also(input::get)
    }

    fun text(maximum: Int): String {
        val bytes = bytes(maximum)
        return Charsets.UTF_8
            .newDecoder()
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    fun finish() {
        require(!input.hasRemaining()) { "trailing resource checkpoint bytes" }
    }
}
