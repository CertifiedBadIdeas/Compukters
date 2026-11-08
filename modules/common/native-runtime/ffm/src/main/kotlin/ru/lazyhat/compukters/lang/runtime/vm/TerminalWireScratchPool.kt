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

package ru.lazyhat.compukters.lang.runtime.vm

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Temporary transport bytes are shared across sessions, exclusively leased through decoding. */
internal class TerminalWireScratchPool(
    private val maximumBytes: Int,
    private val maximumRetainedBuffers: Int = 8,
) : AutoCloseable {
    private val idle = ArrayDeque<TerminalWireScratch>(maximumRetainedBuffers)
    private var closed = false

    init {
        require(maximumBytes > 0) { "terminal FFM output capacity must be positive" }
        require(maximumRetainedBuffers > 0) { "terminal scratch retention must be positive" }
    }

    fun <T> withScratch(read: (TerminalWireScratch) -> T): T {
        val scratch =
            synchronized(idle) {
                check(!closed) { "terminal scratch pool is closed" }
                idle.removeLastOrNull() ?: TerminalWireScratch(maximumBytes)
            }
        try {
            return read(scratch)
        } finally {
            val retained =
                synchronized(idle) {
                    if (!closed && idle.size < maximumRetainedBuffers) {
                        idle.addLast(scratch)
                        true
                    } else {
                        false
                    }
                }
            if (!retained) scratch.close()
        }
    }

    override fun close() {
        val released =
            synchronized(idle) {
                if (closed) return
                closed = true
                idle.toList().also { idle.clear() }
            }
        released.forEach(TerminalWireScratch::close)
    }
}

internal class TerminalWireScratch(
    maximumBytes: Int,
) : AutoCloseable {
    private val arena = Arena.ofShared()
    val output: MemorySegment
    val written: MemorySegment
    val buffer: ByteBuffer

    init {
        try {
            output = arena.allocate(maximumBytes.toLong())
            written = arena.allocate(ValueLayout.JAVA_LONG)
            buffer = output.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
        } catch (failure: Throwable) {
            arena.close()
            throw failure
        }
    }

    override fun close() = arena.close()
}
