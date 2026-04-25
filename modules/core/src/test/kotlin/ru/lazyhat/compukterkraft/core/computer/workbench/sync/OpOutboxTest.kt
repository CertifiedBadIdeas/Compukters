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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.lazyhat.compukterkraft.core.computer.workbench.sync

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.AtomId
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Note on virtual-time helpers:
 * - `advanceTimeBy(N)` followed by `runCurrent()` is used instead of `advanceUntilIdle()`
 *   because the stale timer (5s by default) is also a scheduled delay; `advanceUntilIdle`
 *   would unconditionally fire it and drive every test into the [SyncStatus.Stale] branch.
 */
class OpOutboxTest {

    private val site = SiteId.player(UUID(0, 1))

    private fun insertOp(clock: Int, text: String = "x"): Op.Insert =
        Op.Insert(author = site, clock = clock, leftId = null, text = text)

    @Test
    fun debouncesEnqueuesIntoSingleSend() = runTest {
        val sent = mutableListOf<List<Op>>()
        val outbox = OpOutbox(this, send = { sent.add(it) }, debounceMs = 50, maxBatch = 64)

        repeat(3) { outbox.enqueue(insertOp(clock = it)) }
        advanceTimeBy(40)
        runCurrent()
        assertTrue(sent.isEmpty(), "should not flush before debounce window")
        assertEquals(3, outbox.pendingCount.value)
        assertEquals(SyncStatus.Pending, outbox.status.value)

        advanceTimeBy(20)
        runCurrent()
        assertEquals(1, sent.size, "exactly one batch after debounce")
        assertEquals(3, sent[0].size)
        assertEquals(0, outbox.pendingCount.value)
        assertEquals(SyncStatus.Syncing, outbox.status.value)
    }

    @Test
    fun maxBatchTriggersImmediateFlush() = runTest {
        val sent = mutableListOf<List<Op>>()
        val outbox = OpOutbox(this, send = { sent.add(it) }, debounceMs = 1_000, maxBatch = 4)

        repeat(4) { outbox.enqueue(insertOp(clock = it)) }
        runCurrent()

        assertEquals(1, sent.size)
        assertEquals(4, sent[0].size)
        assertEquals(SyncStatus.Syncing, outbox.status.value)
    }

    @Test
    fun flushNowEmitsAndClearsQueue() = runTest {
        val sent = mutableListOf<List<Op>>()
        val outbox = OpOutbox(this, send = { sent.add(it) }, debounceMs = 1_000, maxBatch = 64)

        outbox.enqueue(insertOp(clock = 0))
        outbox.flushNow()

        assertEquals(1, sent.size)
        assertEquals(0, outbox.pendingCount.value)
        assertEquals(SyncStatus.Syncing, outbox.status.value)
    }

    @Test
    fun ackForLastSentClockTransitionsToIdle() = runTest {
        val outbox = OpOutbox(this, send = {}, debounceMs = 0, maxBatch = 64)

        // Insert "hi" consumes clocks 0..1; highest = 1.
        outbox.enqueue(insertOp(clock = 0, text = "hi"))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SyncStatus.Syncing, outbox.status.value)

        outbox.onAck(ackedClock = 0)
        assertEquals(SyncStatus.Syncing, outbox.status.value, "partial ack stays Syncing")

        outbox.onAck(ackedClock = 1)
        assertEquals(SyncStatus.Idle, outbox.status.value)
    }

    @Test
    fun staleAfterTimeoutWithoutAck() = runTest {
        val outbox = OpOutbox(this, send = {}, debounceMs = 0, maxBatch = 64, staleAfterMs = 1_000)

        outbox.enqueue(insertOp(clock = 0))
        advanceTimeBy(10)
        runCurrent()
        assertEquals(SyncStatus.Syncing, outbox.status.value)

        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(SyncStatus.Stale, outbox.status.value)

        // Recovering ack snaps back to Idle.
        outbox.onAck(ackedClock = 0)
        assertEquals(SyncStatus.Idle, outbox.status.value)
    }

    @Test
    fun newEnqueueAfterFlushSchedulesAnotherDebounce() = runTest {
        val sent = mutableListOf<List<Op>>()
        val outbox = OpOutbox(this, send = { sent.add(it) }, debounceMs = 50, maxBatch = 64)

        outbox.enqueue(insertOp(clock = 0))
        advanceTimeBy(60)
        runCurrent()
        assertEquals(1, sent.size)

        outbox.enqueue(insertOp(clock = 1))
        assertEquals(SyncStatus.Pending, outbox.status.value)
        advanceTimeBy(60)
        runCurrent()
        assertEquals(2, sent.size)
    }

    @Test
    fun deleteOpUsesItsOwnClockForAckTracking() = runTest {
        val outbox = OpOutbox(this, send = {}, debounceMs = 0, maxBatch = 64)
        val target = AtomId(SiteId.ServerInit, 0)

        outbox.enqueue(Op.Delete(author = site, clock = 7, targetId = target, length = 1))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SyncStatus.Syncing, outbox.status.value)

        outbox.onAck(ackedClock = 6)
        assertEquals(SyncStatus.Syncing, outbox.status.value)

        outbox.onAck(ackedClock = 7)
        assertEquals(SyncStatus.Idle, outbox.status.value)
    }
}
