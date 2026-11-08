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

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ReusableTerminalWireTransportTest {
    @Test
    fun `one transport reuses native scratch while separate transports remain isolated`() {
        val firstAddresses = mutableListOf<Pair<Long, Long>>()
        val secondAddresses = mutableListOf<Pair<Long, Long>>()
        val full = fullStateWire(revision = 4)
        val unchanged = unchangedWire(revision = 4)
        val firstPool = TerminalWireScratchPool(8 * 1024)
        val secondPool = TerminalWireScratchPool(8 * 1024)
        val first = transport(firstPool, firstAddresses, full, unchanged)
        val second = transport(secondPool, secondAddresses, full, unchanged)

        assertEquals(4, first.fullState(7).revision)
        assertEquals(4, first.fullState(7).revision)
        assertEquals(TerminalUpdate.Unchanged(4), first.changesSince(7, 4))
        assertEquals(TerminalUpdate.Unchanged(4), first.changesSince(7, 4))
        assertEquals(4, second.fullState(8).revision)

        assertEquals(1, firstAddresses.map(Pair<Long, Long>::first).distinct().size)
        assertEquals(1, firstAddresses.map(Pair<Long, Long>::second).distinct().size)
        assertNotEquals(firstAddresses.first().first, secondAddresses.first().first)

        first.close()
        first.close()
        second.close()
        firstPool.close()
        secondPool.close()
        assertFailsWith<IllegalStateException> { first.fullState(7) }
    }

    @Test
    fun `shared scratch preserves decoded results and closing one session leaves others usable`() {
        TerminalWireScratchPool(TerminalHistoryLimits.NATIVE_BUFFER_BYTES).use { pool ->
            val addresses = mutableListOf<Pair<Long, Long>>()
            val first = transport(pool, addresses, fullStateWire(4, 512), unchangedWire(4))
            val second = transport(pool, addresses, fullStateWire(8), unchangedWire(8))
            val history = first.fullState(7)
            assertEquals(512 * 51, history.historyCells.size)
            assertEquals(8, second.fullState(8).revision)
            assertEquals(4, history.revision)
            assertEquals(512 * 51, history.historyCells.size)
            assertEquals(1, addresses.map { it.first }.distinct().size)
            first.close()
            assertEquals(TerminalUpdate.Unchanged(8), second.changesSince(8, 8))
            assertFailsWith<IllegalStateException> { first.fullState(7) }
            second.close()
        }
    }

    @Test
    fun `parallel readers exclusively lease scratch through decoding`() {
        TerminalWireScratchPool(8 * 1024, maximumRetainedBuffers = 2).use { pool ->
            Executors.newFixedThreadPool(8).use { workers ->
                val barrier = CyclicBarrier(8)
                val calls =
                    (1L..8L).map { revision ->
                        workers.submit<TerminalState> {
                            val bytes = fullStateWire(revision)
                            val transport =
                                ReusableTerminalWireTransport(
                                    pool,
                                    TerminalFullStateCall { _, output, _, written ->
                                        MemorySegment.copy(bytes, 0, output, ValueLayout.JAVA_BYTE, 0, bytes.size)
                                        written.set(ValueLayout.JAVA_LONG, 0, bytes.size.toLong())
                                        barrier.await(10, TimeUnit.SECONDS)
                                        0
                                    },
                                    TerminalChangesSinceCall { _, _, _, _, _ -> error("not used") },
                                )
                            transport.use { it.fullState(revision) }
                        }
                    }
                calls.forEachIndexed { index, result ->
                    assertEquals(index + 1L, result.get(15, TimeUnit.SECONDS).revision)
                }
            }
        }
    }

    @Test
    fun `failed reads return their lease and pool close releases retained native memory`() {
        val pool = TerminalWireScratchPool(8 * 1024)
        var retained: MemorySegment? = null
        var failing = true
        val bytes = fullStateWire(9)
        val transport =
            ReusableTerminalWireTransport(
                pool,
                TerminalFullStateCall { _, output, _, written ->
                    retained = output
                    if (failing) {
                        failing = false
                        error("native read failed")
                    }
                    MemorySegment.copy(bytes, 0, output, ValueLayout.JAVA_BYTE, 0, bytes.size)
                    written.set(ValueLayout.JAVA_LONG, 0, bytes.size.toLong())
                    0
                },
                TerminalChangesSinceCall { _, _, _, _, _ -> error("not used") },
            )
        assertFailsWith<IllegalStateException> { transport.fullState(7) }
        val first = requireNotNull(retained)
        assertEquals(9, transport.fullState(7).revision)
        assertEquals(first.address(), requireNotNull(retained).address())
        pool.close()
        pool.close()
        assertFailsWith<IllegalStateException> { first.get(ValueLayout.JAVA_BYTE, 0) }
        assertFailsWith<IllegalStateException> { transport.fullState(7) }
        transport.close()
    }

    @Test
    fun `excess concurrent scratch closes instead of expanding idle retention`() {
        TerminalWireScratchPool(8 * 1024, maximumRetainedBuffers = 2).use { pool ->
            val loans = mutableListOf<MemorySegment>()
            pool.withScratch { first ->
                loans += first.output
                pool.withScratch { second ->
                    loans += second.output
                    pool.withScratch { third -> loans += third.output }
                }
            }
            assertEquals(2, loans.count { it.scope().isAlive })
        }
    }

    @Test
    fun `pool shutdown preserves an outstanding loan until the reader returns it`() {
        val pool = TerminalWireScratchPool(8 * 1024)
        lateinit var loan: MemorySegment
        pool.withScratch { scratch ->
            loan = scratch.output
            pool.close()
            scratch.output.set(ValueLayout.JAVA_BYTE, 0, 7)
            assertEquals(7.toByte(), scratch.output.get(ValueLayout.JAVA_BYTE, 0))
        }
        assertFailsWith<IllegalStateException> { loan.get(ValueLayout.JAVA_BYTE, 0) }
    }

    private fun transport(
        pool: TerminalWireScratchPool,
        addresses: MutableList<Pair<Long, Long>>,
        full: ByteArray,
        unchanged: ByteArray,
    ): ReusableTerminalWireTransport =
        ReusableTerminalWireTransport(
            scratchPool = pool,
            fullStateCall =
                TerminalFullStateCall { _, output, _, written ->
                    writeResult(addresses, output, written, full)
                },
            changesSinceCall =
                TerminalChangesSinceCall { _, _, output, _, written ->
                    writeResult(addresses, output, written, unchanged)
                },
        )

    private fun writeResult(
        addresses: MutableList<Pair<Long, Long>>,
        output: MemorySegment,
        written: MemorySegment,
        bytes: ByteArray,
    ): Int {
        addresses += output.address() to written.address()
        MemorySegment.copy(bytes, 0, output, ValueLayout.JAVA_BYTE, 0, bytes.size)
        written.set(ValueLayout.JAVA_LONG, 0, bytes.size.toLong())
        return 0
    }

    private fun unchangedWire(revision: Long): ByteArray =
        ByteBuffer
            .allocate(1 + Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .putLong(revision)
            .array()

    private fun fullStateWire(
        revision: Long,
        historyRows: Int = 0,
    ): ByteArray =
        ByteBuffer
            .allocate(
                1 + Long.SIZE_BYTES + 2 * Short.SIZE_BYTES + Int.SIZE_BYTES + 969 * 6 + 2 * Short.SIZE_BYTES + 1 + Long.SIZE_BYTES +
                    Int.SIZE_BYTES + historyRows * 51 * 6,
            ).order(ByteOrder.LITTLE_ENDIAN)
            .put(2)
            .putLong(revision)
            .putShort(51)
            .putShort(19)
            .putInt(969)
            .apply {
                repeat(969) {
                    putInt(' '.code)
                    put(15)
                    put(0)
                }
            }.putShort(0)
            .putShort(0)
            .put(1)
            .putLong(0)
            .putInt(historyRows * 51)
            .apply {
                repeat(historyRows * 51) {
                    putInt('h'.code)
                    put(15)
                    put(0)
                }
            }.array()
}
