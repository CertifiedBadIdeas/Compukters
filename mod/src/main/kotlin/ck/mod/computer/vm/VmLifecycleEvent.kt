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

import ck.lang.runtime.VmStopReason

/**
 * Events emitted by the VM coroutine when a lifecycle transition occurs.
 *
 * Consumers (e.g. [ck.mod.computer.ServerComputer]) collect the
 * [BackgroundComputerVm.lifecycleEvents] [SharedFlow][kotlinx.coroutines.flow.SharedFlow]
 * to react to these transitions.
 */
sealed interface VmLifecycleEvent {
    /** The VM has stopped (normally, by request, or due to a crash). */
    data class Stopped(val reason: VmStopReason) : VmLifecycleEvent

    /** The VM has requested a reboot (stop + restart). */
    data object RebootRequested : VmLifecycleEvent
}
