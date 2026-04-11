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

package ru.lazyhat.compukterkraft.core.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerBlockPolicyTest {
    @Test
    fun createsNorthFacingOffStateByDefault() {
        val defaults = ComputerBlockPolicy.defaultState()

        assertEquals(HorizontalFacingModel.NORTH, defaults.facing)
        assertEquals(ComputerVisualStateModel.OFF, defaults.state)
    }

    @Test
    fun placementFacingOpposesPlayerFacing() {
        assertEquals(HorizontalFacingModel.SOUTH, ComputerBlockPolicy.placementFacing(HorizontalFacingModel.NORTH))
        assertEquals(HorizontalFacingModel.WEST, ComputerBlockPolicy.placementFacing(HorizontalFacingModel.EAST))
    }

    @Test
    fun horizontalFacingOpposite() {
        assertEquals(HorizontalFacingModel.SOUTH, HorizontalFacingModel.NORTH.opposite())
        assertEquals(HorizontalFacingModel.NORTH, HorizontalFacingModel.SOUTH.opposite())
        assertEquals(HorizontalFacingModel.WEST, HorizontalFacingModel.EAST.opposite())
        assertEquals(HorizontalFacingModel.EAST, HorizontalFacingModel.WEST.opposite())
    }

    @Test
    fun crouchingPlayerDoesNotOpenMenu() {
        assertTrue(ComputerBlockPolicy.shouldOpenMenu(isPlayerCrouching = false))
        assertFalse(ComputerBlockPolicy.shouldOpenMenu(isPlayerCrouching = true))
    }
}