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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetainedDisplaySessionTrackerTest {
    @Test
    fun allocatesDeterministicTokensAndReauthorizesReopenedDisplay() {
        val tracker = RetainedDisplaySessionTracker()
        val firstPlayer = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002")

        val first = tracker.attach(firstPlayer, containerId = 11, displayId = 4)
        val second = tracker.attach(secondPlayer, containerId = 12, displayId = 4)
        val reopened = tracker.attach(firstPlayer, containerId = 13, displayId = 4)

        assertEquals(1L, first.viewerToken)
        assertEquals(2L, second.viewerToken)
        assertEquals(first.viewerToken, reopened.viewerToken)
        assertNull(tracker.authorize(firstPlayer, containerId = 11, displayId = 4))
        assertEquals(1L, tracker.authorize(firstPlayer, containerId = 13, displayId = 4))
        assertNull(tracker.detach(firstPlayer, containerId = 11, displayId = 4))
        assertEquals(1L, tracker.detach(firstPlayer, containerId = 13, displayId = 4))
        assertNull(tracker.authorize(firstPlayer, containerId = 13, displayId = 4))
    }
}
