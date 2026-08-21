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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetainedDisplaySessionTrackerTest {
    @Test
    fun ownsOneStableViewerSessionPerPlayer() {
        val tracker = RetainedDisplaySessionTracker()
        val firstPlayer = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002")

        val first = tracker.attach(firstPlayer)
        val second = tracker.attach(secondPlayer)
        val reopened = tracker.attach(firstPlayer)

        assertEquals(1L, first.viewerToken)
        assertEquals(2L, second.viewerToken)
        assertEquals(first, reopened)
        assertEquals(1L, tracker.authorize(firstPlayer))
        assertNull(tracker.authorize(UUID.fromString("00000000-0000-0000-0000-000000000003")))
        assertEquals(reopened, tracker.sessionForToken(1L))
        assertEquals(1L, tracker.detach(firstPlayer))
        assertNull(tracker.detach(firstPlayer))
        assertNull(tracker.authorize(firstPlayer))
        assertNull(tracker.sessionForToken(1L))
    }
}
