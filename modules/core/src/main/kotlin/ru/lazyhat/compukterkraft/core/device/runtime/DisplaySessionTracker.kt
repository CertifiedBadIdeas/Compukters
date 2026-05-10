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

package ru.lazyhat.compukterkraft.core.device.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class DisplayEndpoint(
    val displayId: Int,
    val width: Int,
    val height: Int,
)

internal data class DisplaySession(
    val playerUuid: UUID,
    var containerId: Int,
    val displayId: Int,
    var width: Int,
    var height: Int,
)

internal class DisplaySessionTracker {
    private val sessions = ConcurrentHashMap<Pair<UUID, Int>, DisplaySession>()

    fun attach(
        playerUuid: UUID,
        containerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    ): DisplayEndpoint {
        sessions[playerUuid to displayId] = DisplaySession(playerUuid, containerId, displayId, width, height)
        return DisplayEndpoint(displayId, width, height)
    }

    fun resize(
        playerUuid: UUID,
        displayId: Int,
        width: Int,
        height: Int,
    ): DisplayEndpoint? {
        val session = sessions[playerUuid to displayId] ?: return null
        session.width = width
        session.height = height
        return DisplayEndpoint(displayId, width, height)
    }

    fun detach(
        playerUuid: UUID,
        displayId: Int,
    ): Int? {
        sessions.remove(playerUuid to displayId) ?: return null
        return if (sessions.values.none { it.displayId == displayId }) displayId else null
    }

    fun activeEndpoints(): List<DisplayEndpoint> =
        sessions.values
            .groupBy { it.displayId }
            .map { (_, displaySessions) ->
                val session = displaySessions.first()
                DisplayEndpoint(session.displayId, session.width, session.height)
            }

    fun sessionsSnapshot(): List<DisplaySession> = sessions.values.toList()

    fun sessionKeysSnapshot(): List<Pair<UUID, Int>> = sessions.keys.toList()

    fun isEmpty(): Boolean = sessions.isEmpty()
}
