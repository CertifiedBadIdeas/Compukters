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

import ru.lazyhat.compukterkraft.lang.runtime.HostCall
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason

/**
 * Bundles all VM-internal services that API classes need.
 * Implemented by [BackgroundDeviceVm] — replaces the ~15 lambdas
 * that were previously threaded through every API constructor.
 */
interface VmContext {
    /** Suspend until the next event arrives (deferred events first). */
    suspend fun receiveEvent(): VmEvent

    /** Return the next available event without blocking, or null when none is queued. */
    fun tryReceiveEvent(): VmEvent?

    /** Push an event back so it will be returned by the next [receiveEvent]. */
    fun deferEvent(event: VmEvent)

    /** Update the VM lifecycle state visible in snapshots. */
    fun setState(state: VmState)

    /** Set or clear the sleep-until tick. */
    fun setSleepUntil(tick: Long?)

    /** Cooperative scheduling point — yields the time-slice when the budget is exhausted. */
    suspend fun schedulingPoint()

    /** Post a [HostCall] to the server main thread and suspend until the result arrives. */
    suspend fun <T> awaitHostCall(callFactory: (Long) -> HostCall): T

    /** Resolve a relative path against the current working directory. */
    fun resolvePath(path: String): String

    /** Enqueue a VM event (non-suspending, from any thread). */
    fun enqueueEvent(event: VmEvent): Boolean

    /** Request the VM to stop. */
    fun stop(reason: VmStopReason)

    /** Log a message through the VM logger. */
    fun log(message: String)
}
