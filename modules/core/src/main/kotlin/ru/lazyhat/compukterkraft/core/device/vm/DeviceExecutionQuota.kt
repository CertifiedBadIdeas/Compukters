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

import kotlinx.coroutines.channels.Channel

/**
 * Bounded device-level execution quota.
 *
 * This intentionally preserves the old one-pending-slice behavior while making the quota a named runtime primitive
 * instead of an anonymous channel. Later scheduler slices can add numeric instruction/time accounting here.
 */
internal class DeviceExecutionQuota {
    private val permits = Channel<Unit>(capacity = 1)

    fun refill(available: Boolean): Boolean {
        if (!available) return false
        return permits.trySend(Unit).isSuccess
    }

    suspend fun awaitPermit() {
        permits.receive()
    }
}
