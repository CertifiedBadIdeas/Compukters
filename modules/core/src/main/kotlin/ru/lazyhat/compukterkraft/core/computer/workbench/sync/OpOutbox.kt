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

package ru.lazyhat.compukterkraft.core.computer.workbench.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op

/**
 * Client-side outbox that batches outgoing CRDT ops, debounces network sends, and surfaces a
 * reactive [SyncStatus] / [pendingCount] for the UI.
 *
 * Design:
 * - Edits hit [enqueue]; the outbox accumulates them in a queue and schedules a flush after
 *   [debounceMs] of quiescence. If the queue grows to [maxBatch], the flush fires immediately.
 * - When the queue flushes, the outbox launches [send] in [scope] and starts a stale timer; if
 *   no ack arrives within [staleAfterMs], status transitions to [SyncStatus.Stale]. The editor
 *   stays usable in Stale; the next ack snaps status back to Idle.
 * - [pendingCount] reflects queued (not-yet-sent) ops only; in-flight work is implied by
 *   `status == Syncing`.
 *
 * Thread-safety: API methods (`enqueue`, `flushNow`, `onAck`) are safe to call from any thread;
 * mutation is guarded by `synchronized(this)`. Internal jobs run on [scope].
 */
class OpOutbox(
    private val scope: CoroutineScope,
    private val send: suspend (List<Op>) -> Unit,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    private val queued: ArrayDeque<Op> = ArrayDeque()
    private var lastSentMaxClock: Int = -1
    private var lastAckedClock: Int = -1
    private var debounceJob: Job? = null
    private var staleJob: Job? = null
    private var sendJob: Job? = null

    private val _status: MutableStateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status

    private val _pendingCount: MutableStateFlow<Int> = MutableStateFlow(0)

    /** Number of ops currently sitting in the outbox queue (not yet sent). */
    val pendingCount: StateFlow<Int> = _pendingCount

    fun enqueue(op: Op) {
        synchronized(this) {
            queued.add(op)
            updateStateLocked()
            if (queued.size >= maxBatch) {
                flushLocked()
            } else {
                scheduleDebounceLocked()
            }
        }
    }

    /**
     * Force an immediate flush of the queue and suspend until the in-flight [send] completes.
     * Used by `WorkbenchStore.flushAndRun` to guarantee the server has seen our latest ops
     * before issuing RUN.
     */
    suspend fun flushNow() {
        val running =
            synchronized(this) {
                flushLocked()
                sendJob
            }
        running?.join()
    }

    fun onAck(ackedClock: Int) {
        synchronized(this) {
            if (ackedClock > lastAckedClock) lastAckedClock = ackedClock
            if (lastAckedClock >= lastSentMaxClock) {
                staleJob?.cancel()
                staleJob = null
            }
            updateStateLocked()
        }
    }

    private fun scheduleDebounceLocked() {
        debounceJob?.cancel()
        debounceJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                delay(debounceMs)
                synchronized(this@OpOutbox) { flushLocked() }
            }
    }

    private fun flushLocked() {
        if (queued.isEmpty()) return
        val batch = queued.toList()
        queued.clear()
        debounceJob?.cancel()
        debounceJob = null

        val highest = batch.maxOf { highestClockOf(it) }
        if (highest > lastSentMaxClock) lastSentMaxClock = highest

        sendJob = scope.launch { send(batch) }
        scheduleStaleLocked()
        updateStateLocked()
    }

    private fun scheduleStaleLocked() {
        staleJob?.cancel()
        staleJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                delay(staleAfterMs)
                synchronized(this@OpOutbox) {
                    if (lastAckedClock < lastSentMaxClock) {
                        _status.value = SyncStatus.Stale
                    }
                }
            }
    }

    private fun updateStateLocked() {
        _pendingCount.value = queued.size
        _status.value =
            when {
                queued.isNotEmpty() -> {
                    SyncStatus.Pending
                }

                lastSentMaxClock > lastAckedClock -> {
                    if (_status.value == SyncStatus.Stale) SyncStatus.Stale else SyncStatus.Syncing
                }

                else -> {
                    SyncStatus.Idle
                }
            }
    }

    private fun highestClockOf(op: Op): Int =
        when (op) {
            is Op.Insert -> op.clock + op.text.length - 1
            is Op.Delete -> op.clock
        }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 50
        const val DEFAULT_MAX_BATCH: Int = 64
        const val DEFAULT_STALE_AFTER_MS: Long = 5_000
    }
}
