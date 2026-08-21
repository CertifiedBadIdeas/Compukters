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

package ru.lazyhat.compukters.core.device.runtime

import java.util.UUID

internal data class RetainedDisplaySession(
    val playerUuid: UUID,
    val viewerToken: Long,
)

internal class RetainedDisplaySessionTracker {
    private val sessions = linkedMapOf<UUID, RetainedDisplaySession>()
    private var nextViewerToken = 1L

    @Synchronized
    fun attach(playerUuid: UUID): RetainedDisplaySession =
        sessions.getOrPut(playerUuid) {
            RetainedDisplaySession(playerUuid, allocateViewerToken())
        }

    @Synchronized
    fun authorize(playerUuid: UUID): Long? = sessions[playerUuid]?.viewerToken

    @Synchronized
    fun detach(playerUuid: UUID): Long? = sessions.remove(playerUuid)?.viewerToken

    @Synchronized
    fun sessionForToken(viewerToken: Long): RetainedDisplaySession? = sessions.values.firstOrNull { it.viewerToken == viewerToken }

    @Synchronized
    fun sessionsSnapshot(): List<RetainedDisplaySession> = sessions.values.toList()

    @Synchronized
    fun clear(): List<Long> {
        val tokens = sessions.values.map(RetainedDisplaySession::viewerToken)
        sessions.clear()
        return tokens
    }

    private fun allocateViewerToken(): Long {
        check(nextViewerToken > 0) { "Retained display viewer token space is exhausted" }
        val token = nextViewerToken
        nextViewerToken = if (token == Long.MAX_VALUE) 0 else token + 1
        return token
    }
}
