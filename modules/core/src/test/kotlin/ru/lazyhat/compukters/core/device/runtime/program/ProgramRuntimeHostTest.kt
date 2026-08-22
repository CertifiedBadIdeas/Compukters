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

package ru.lazyhat.compukters.core.device.runtime.program

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProgramRuntimeHostTest {
    @Test
    fun `execution limits must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(
                guestBudgetPerAdvance = 0,
                maintenanceBudgetPerAdvance = 1,
                maximumAdvancesPerTick = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(
                guestBudgetPerAdvance = 1,
                maintenanceBudgetPerAdvance = 0,
                maximumAdvancesPerTick = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(
                guestBudgetPerAdvance = 1,
                maintenanceBudgetPerAdvance = 1,
                maximumAdvancesPerTick = 0,
            )
        }
    }

    @Test
    fun `terminal limits must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ProgramTerminalLimits(
                maximumInputLineCodeUnits = 0,
                maximumPendingOutputCodeUnits = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgramTerminalLimits(
                maximumInputLineCodeUnits = 1,
                maximumPendingOutputCodeUnits = 0,
            )
        }
    }

    @Test
    fun `defaults expose a bounded idle runtime model`() {
        assertEquals(4_096, ProgramTickBudget().guestBudgetPerAdvance)
        assertEquals(256, ProgramTickBudget().maintenanceBudgetPerAdvance)
        assertEquals(32, ProgramTickBudget().maximumAdvancesPerTick)
        assertEquals(4_096, ProgramTerminalLimits().maximumInputLineCodeUnits)
        assertEquals(65_536, ProgramTerminalLimits().maximumPendingOutputCodeUnits)
        assertEquals(ProgramRuntimeState.Idle, ProgramRuntimeState.Idle)
    }
}
