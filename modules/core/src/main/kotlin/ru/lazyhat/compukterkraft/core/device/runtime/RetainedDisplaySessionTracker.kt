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

internal data class RetainedDisplaySession(
    val playerUuid: UUID,
    val containerId: Int,
    val displayId: Int,
    val viewerToken: Long,
)

internal class RetainedDisplaySessionTracker {
    private val sessions = linkedMapOf<Pair<UUID, Int>, RetainedDisplaySession>()
    private var nextViewerToken = 1L

    @Synchronized
    fun attach(
        playerUuid: UUID,
        containerId: Int,
        displayId: Int,
    ): RetainedDisplaySession {
        val key = playerUuid to displayId
        val token = sessions[key]?.viewerToken ?: allocateViewerToken()
        return RetainedDisplaySession(playerUuid, containerId, displayId, token).also { sessions[key] = it }
    }

    @Synchronized
    fun authorize(
        playerUuid: UUID,
        containerId: Int,
        displayId: Int,
    ): Long? =
        sessions[playerUuid to displayId]
            ?.takeIf { it.containerId == containerId }
            ?.viewerToken

    @Synchronized
    fun detach(
        playerUuid: UUID,
        containerId: Int,
        displayId: Int,
    ): Long? {
        val key = playerUuid to displayId
        val session = sessions[key]?.takeIf { it.containerId == containerId } ?: return null
        sessions.remove(key)
        return session.viewerToken
    }

    @Synchronized
    fun sessionForToken(viewerToken: Long): RetainedDisplaySession? = sessions.values.firstOrNull { it.viewerToken == viewerToken }

    @Synchronized
    fun clear() {
        sessions.clear()
    }

    private fun allocateViewerToken(): Long {
        check(nextViewerToken > 0) { "Retained display viewer token space is exhausted" }
        val token = nextViewerToken
        nextViewerToken = if (token == Long.MAX_VALUE) 0 else token + 1
        return token
    }
}
