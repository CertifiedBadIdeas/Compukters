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

package ck.mod.computer.vm

import ck.lang.runtime.VmState
import ck.lang.runtime.VmStopReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks the VM lifecycle progression: state, stop reason, error message.
 * Guarded by a [Mutex] for the stop transition.
 */
class VmLifecycleState {
    private val lock = Mutex()

    @Volatile
    var state: VmState = VmState.COLD
        private set

    @Volatile
    var stopReason: VmStopReason? = null
        private set

    @Volatile
    var errorMessage: String? = null
        private set

    val isBooting: Boolean get() = state == VmState.BOOTING

    val isStopped: Boolean get() = state == VmState.STOPPED || state == VmState.CRASHED

    fun setState(newState: VmState) {
        state = newState
    }

    suspend fun withLock(block: suspend () -> Unit) {
        lock.withLock { block() }
    }

    suspend fun stopVm(reason: VmStopReason, error: String? = null) {
        lock.withLock {
            stopReason = reason
            errorMessage = error
            state = if (reason == VmStopReason.CRASHED) VmState.CRASHED else VmState.STOPPED
        }
    }
}

/**
 * Tracks tick-based scheduling data: current tick, sleep deadline, slice budget.
 * All fields are [Volatile] — no mutex needed, they're updated from a single writer (server tick)
 * or the VM coroutine.
 */
class VmSchedulingState {
    @Volatile
    var currentTick: Long = 0
        private set

    @Volatile
    var sleepUntilTick: Long? = null
        private set

    @Volatile
    var sliceDeadlineNanos: Long = 0
        private set

    fun updateCurrentTick(tick: Long) {
        currentTick = tick
    }

    fun updateSliceDeadlineNanos(budgetNanos: Long) {
        sliceDeadlineNanos = System.nanoTime() + budgetNanos
    }

    suspend fun setSleepUntil(tick: Long?) {
        sleepUntilTick = tick
    }

    fun shouldWake(tick: Long): Boolean = sleepUntilTick?.let { tick >= it } ?: true
}

/**
 * Combined manager that delegates to [VmLifecycleState] and [VmSchedulingState].
 * Exists for backward compatibility during the refactoring — callers can migrate
 * to the split classes gradually.
 */
class VmStateManager {
    val lifecycle = VmLifecycleState()
    val scheduling = VmSchedulingState()

    // ── Lifecycle delegates ─────────────────────────────────────────

    val state: VmState get() = lifecycle.state
    val stopReason: VmStopReason? get() = lifecycle.stopReason
    val errorMessage: String? get() = lifecycle.errorMessage
    val isBooting: Boolean get() = lifecycle.isBooting
    val isStopped: Boolean get() = lifecycle.isStopped

    fun setState(newState: VmState) = lifecycle.setState(newState)

    suspend fun withStateLock(block: suspend () -> Unit) = lifecycle.withLock(block)

    suspend fun stopVm(reason: VmStopReason, error: String? = null) = lifecycle.stopVm(reason, error)

    // ── Scheduling delegates ────────────────────────────────────────

    val currentTick: Long get() = scheduling.currentTick
    val sleepUntilTick: Long? get() = scheduling.sleepUntilTick
    val sliceDeadlineNanos: Long get() = scheduling.sliceDeadlineNanos

    fun updateCurrentTick(tick: Long) = scheduling.updateCurrentTick(tick)
    fun updateSliceDeadlineNanos(budgetNanos: Long) = scheduling.updateSliceDeadlineNanos(budgetNanos)
    suspend fun setSleepUntil(tick: Long?) = scheduling.setSleepUntil(tick)
}
