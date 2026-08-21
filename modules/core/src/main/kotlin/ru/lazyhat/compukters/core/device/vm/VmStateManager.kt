/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.core.device.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lazyhat.compukters.core.LOGGER
import ru.lazyhat.compukters.lang.runtime.VmState
import ru.lazyhat.compukters.lang.runtime.VmStopReason

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
 * Tracks the last server tick observed by the VM host.
 */
class VmTickState {
    private val _currentTick = MutableStateFlow(0L)

    /** Observable current tick. */
    val currentTickFlow: StateFlow<Long> = _currentTick.asStateFlow()

    /** Current tick (synchronous read). */
    val currentTick: Long get() = _currentTick.value

    fun updateCurrentTick(tick: Long) {
        _currentTick.value = tick
    }
}

/**
 * Combined manager that delegates to [VmLifecycleState] and [VmTickState].
 *
 * Exposes both synchronous value getters (e.g. [state]) and reactive [StateFlow]
 * properties (e.g. [stateFlow]) for each piece of state.
 */
class VmStateManager {
    val lifecycle = VmLifecycleState()
    val ticks = VmTickState()

    // ── Lifecycle delegates ─────────────────────────────────────────

    val state: VmState get() = lifecycle.state
    val stateFlow: StateFlow<VmState> get() = lifecycle.stateFlow
    val isStopped: Boolean get() = lifecycle.isStopped

    fun setState(newState: VmState) = lifecycle.setState(newState)

    suspend fun stopVm(
        reason: VmStopReason,
        error: String? = null,
    ) = lifecycle.stopVm(reason, error)

    // ── Tick delegates ──────────────────────────────────────────────

    val currentTick: Long get() = ticks.currentTick
    val currentTickFlow: StateFlow<Long> get() = ticks.currentTickFlow

    fun updateCurrentTick(tick: Long) = ticks.updateCurrentTick(tick)
}
