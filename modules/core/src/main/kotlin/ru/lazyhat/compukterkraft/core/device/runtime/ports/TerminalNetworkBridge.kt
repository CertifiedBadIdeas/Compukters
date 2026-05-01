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

package ru.lazyhat.compukterkraft.core.device.runtime.ports

import java.util.UUID

/** Bridges per-player stdout byte streams from a runtime device to the network layer,
 *  and answers session-validity questions that depend on platform state (open menus). */
interface TerminalNetworkBridge {
    /** True if [playerUuid] currently has menu [containerId] open and that menu is
     *  still bound to the runtime device identified by [deviceId]. */
    fun isSessionStillBound(
        playerUuid: UUID,
        containerId: Int,
        deviceId: Int,
    ): Boolean

    /** Send raw stdout bytes to the player; no-op if the player is offline. */
    fun sendStdoutBytes(
        playerUuid: UUID,
        containerId: Int,
        bytes: ByteArray,
    )
}
