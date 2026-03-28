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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks the VM lifecycle progression: state, stop reason, error message.
 *
 * All fields are backed by [MutableStateFlow] so that consumers can either
 * read the current value synchronously (`.value`) or observe changes reactively
 * via [StateFlow.collect]. The stop transition is guarded by a [Mutex].
 */
class VmLifecycleState {
    private val lock = Mutex()

    private val _state = MutableStateFlow(VmState.COLD)
    /** Observable lifecycle state. Use [state] for synchronous reads. */
    val stateFlow: StateFlow<VmState> = _state.asStateFlow()
    /** Current lifecycle state (synchronous read). */
    val state: VmState get() = _state.value

    private val _stopReason = MutableStateFlow<VmStopReason?>(null)
    /** Observable stop reason. */
    val stopReasonFlow: StateFlow<VmStopReason?> = _stopReason.asStateFlow()
    /** Current stop reason (synchronous read). */
    val stopReason: VmStopReason? get() = _stopReason.value

    private val _errorMessage = MutableStateFlow<String?>(null)
    /** Observable error message. */
    val errorMessageFlow: StateFlow<String?> = _errorMessage.asStateFlow()
    /** Current error message (synchronous read). */
    val errorMessage: String? get() = _errorMessage.value

    val isBooting: Boolean get() = _state.value == VmState.BOOTING

    val isStopped: Boolean get() = _state.value == VmState.STOPPED || _state.value == VmState.CRASHED

    fun setState(newState: VmState) {
        _state.value = newState
    }

    suspend fun withLock(block: suspend () -> Unit) {
        lock.withLock { block() }
    }

    suspend fun stopVm(reason: VmStopReason, error: String? = null) {
        lock.withLock {
            _stopReason.value = reason
            _errorMessage.value = error
            _state.value = if (reason == VmStopReason.CRASHED) VmState.CRASHED else VmState.STOPPED
        }
    }
}

/**
 * Tracks tick-based scheduling data: current tick, sleep deadline, slice budget.
 *
 * All fields are backed by [MutableStateFlow] — atomic reads/writes, observable
 * via [StateFlow.collect]. Updated from a single writer (server tick thread or VM coroutine).
 */
class VmSchedulingState {
    private val _currentTick = MutableStateFlow(0L)
    /** Observable current tick. */
    val currentTickFlow: StateFlow<Long> = _currentTick.asStateFlow()
    /** Current tick (synchronous read). */
    val currentTick: Long get() = _currentTick.value

    private val _sleepUntilTick = MutableStateFlow<Long?>(null)
    /** Observable sleep-until tick. */
    val sleepUntilTickFlow: StateFlow<Long?> = _sleepUntilTick.asStateFlow()
    /** Sleep-until tick (synchronous read). */
    val sleepUntilTick: Long? get() = _sleepUntilTick.value

    private val _sliceDeadlineNanos = MutableStateFlow(0L)
    /** Observable slice deadline (nanos). */
    val sliceDeadlineNanosFlow: StateFlow<Long> = _sliceDeadlineNanos.asStateFlow()
    /** Slice deadline in nanos (synchronous read). */
    val sliceDeadlineNanos: Long get() = _sliceDeadlineNanos.value

    fun updateCurrentTick(tick: Long) {
        _currentTick.value = tick
    }

    fun updateSliceDeadlineNanos(budgetNanos: Long) {
        _sliceDeadlineNanos.value = System.nanoTime() + budgetNanos
    }

    fun setSleepUntil(tick: Long?) {
        _sleepUntilTick.value = tick
    }

    fun shouldWake(tick: Long): Boolean = _sleepUntilTick.value?.let { tick >= it } ?: true
}

/**
 * Combined manager that delegates to [VmLifecycleState] and [VmSchedulingState].
 *
 * Exposes both synchronous value getters (e.g. [state]) and reactive [StateFlow]
 * properties (e.g. [stateFlow]) for each piece of state.
 */
class VmStateManager {
    val lifecycle = VmLifecycleState()
    val scheduling = VmSchedulingState()

    // ── Lifecycle delegates ─────────────────────────────────────────

    val state: VmState get() = lifecycle.state
    val stateFlow: StateFlow<VmState> get() = lifecycle.stateFlow
    val stopReason: VmStopReason? get() = lifecycle.stopReason
    val stopReasonFlow: StateFlow<VmStopReason?> get() = lifecycle.stopReasonFlow
    val errorMessage: String? get() = lifecycle.errorMessage
    val errorMessageFlow: StateFlow<String?> get() = lifecycle.errorMessageFlow
    val isBooting: Boolean get() = lifecycle.isBooting
    val isStopped: Boolean get() = lifecycle.isStopped

    fun setState(newState: VmState) = lifecycle.setState(newState)

    suspend fun withStateLock(block: suspend () -> Unit) = lifecycle.withLock(block)

    suspend fun stopVm(reason: VmStopReason, error: String? = null) = lifecycle.stopVm(reason, error)

    // ── Scheduling delegates ────────────────────────────────────────

    val currentTick: Long get() = scheduling.currentTick
    val currentTickFlow: StateFlow<Long> get() = scheduling.currentTickFlow
    val sleepUntilTick: Long? get() = scheduling.sleepUntilTick
    val sleepUntilTickFlow: StateFlow<Long?> get() = scheduling.sleepUntilTickFlow
    val sliceDeadlineNanos: Long get() = scheduling.sliceDeadlineNanos
    val sliceDeadlineNanosFlow: StateFlow<Long> get() = scheduling.sliceDeadlineNanosFlow

    fun updateCurrentTick(tick: Long) = scheduling.updateCurrentTick(tick)
    fun updateSliceDeadlineNanos(budgetNanos: Long) = scheduling.updateSliceDeadlineNanos(budgetNanos)
    fun setSleepUntil(tick: Long?) = scheduling.setSleepUntil(tick)
}
