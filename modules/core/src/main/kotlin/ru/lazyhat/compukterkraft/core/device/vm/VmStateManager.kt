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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason

/**
 * Tracks the VM lifecycle as a single [VmState] sealed hierarchy.
 *
 * Terminal information (stop reason, error message) lives inside the sealed subtypes
 * [VmState.Stopped] and [VmState.Crashed], so there is only one [StateFlow] to observe.
 * The stop transition is guarded by a [Mutex].
 */
class VmLifecycleState {
    private val lock = Mutex()

    private val _state = MutableStateFlow<VmState>(VmState.Cold)

    /** Observable lifecycle state. */
    val stateFlow: StateFlow<VmState> = _state.asStateFlow()

    /** Current lifecycle state (synchronous read). */
    val state: VmState get() = _state.value

    val isBooting: Boolean get() = _state.value is VmState.Booting

    val isStopped: Boolean get() = _state.value.isTerminal

    fun setState(newState: VmState) {
        _state.value = newState
    }

    suspend fun withLock(block: suspend () -> Unit) {
        lock.withLock { block() }
    }

    suspend fun stopVm(
        reason: VmStopReason,
        error: String? = null,
    ) {
        LOGGER.debug { "Stopping VM: reason=$reason, error=$error" }
        lock.withLock {
            _state.value = if (error != null) VmState.Crashed(error) else VmState.Stopped(reason)
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
    val isBooting: Boolean get() = lifecycle.isBooting
    val isStopped: Boolean get() = lifecycle.isStopped

    fun setState(newState: VmState) = lifecycle.setState(newState)

    suspend fun stopVm(
        reason: VmStopReason,
        error: String? = null,
    ) = lifecycle.stopVm(reason, error)

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
