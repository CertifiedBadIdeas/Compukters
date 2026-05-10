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

import kotlinx.coroutines.CompletableDeferred
import java.util.ArrayDeque

/**
 * Bounded device-level execution quota.
 *
 * The quota remains one pending permit per device, but the permit is now owned by a selected process. That lets the
 * device scheduler choose which process may resume while preserving the old bounded back-pressure behavior.
 */
internal class DeviceExecutionQuota {
    private val lock = Any()
    private val waiters = mutableMapOf<Int, ArrayDeque<CompletableDeferred<Unit>>>()
    private var pendingPid: Int? = null

    fun refill(selectedPid: Int?): Boolean {
        selectedPid ?: return false
        var waiter: CompletableDeferred<Unit>? = null
        synchronized(lock) {
            if (pendingPid != null) {
                return false
            }
            waiter = waiters[selectedPid]?.pollFirst()
            if (waiters[selectedPid]?.isEmpty() == true) {
                waiters.remove(selectedPid)
            }
            if (waiter == null) {
                pendingPid = selectedPid
            }
        }
        waiter?.complete(Unit)
        return true
    }

    suspend fun awaitPermit(processId: Int) {
        val waiter =
            synchronized(lock) {
                if (pendingPid == processId) {
                    pendingPid = null
                    return
                }
                CompletableDeferred<Unit>().also { deferred ->
                    waiters.getOrPut(processId) { ArrayDeque() }.addLast(deferred)
                }
            }
        waiter.await()
    }
}
