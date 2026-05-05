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

package ru.lazyhat.compukterkraft.core.device.vm.display

import kotlin.test.Test
import kotlin.test.assertEquals

class TileDirtyTrackerTest {
    @Test
    fun marksEveryTileTouchedByRectangleOnceInStableOrder() {
        val tracker = TileDirtyTracker(width = 40, height = 24, tileSize = 16)

        tracker.markRectDirty(x = 15, y = 15, width = 20, height = 9)

        assertEquals(
            listOf(
                DirtyTile(tileX = 0, tileY = 0, x = 0, y = 0, width = 16, height = 16),
                DirtyTile(tileX = 1, tileY = 0, x = 16, y = 0, width = 16, height = 16),
                DirtyTile(tileX = 2, tileY = 0, x = 32, y = 0, width = 8, height = 16),
                DirtyTile(tileX = 0, tileY = 1, x = 0, y = 16, width = 16, height = 8),
                DirtyTile(tileX = 1, tileY = 1, x = 16, y = 16, width = 16, height = 8),
                DirtyTile(tileX = 2, tileY = 1, x = 32, y = 16, width = 8, height = 8),
            ),
            tracker.dirtyTiles(),
        )
    }

    @Test
    fun ignoresRectanglesOutsideTheDisplay() {
        val tracker = TileDirtyTracker(width = 32, height = 32, tileSize = 16)

        tracker.markRectDirty(x = 40, y = 40, width = 5, height = 5)
        assertEquals(emptyList(), tracker.dirtyTiles())
    }
}
