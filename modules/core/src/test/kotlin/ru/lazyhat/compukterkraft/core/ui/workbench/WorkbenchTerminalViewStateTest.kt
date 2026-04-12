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

package ru.lazyhat.compukterkraft.core.ui.workbench

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import kotlin.test.Test
import kotlin.test.assertIs

class WorkbenchTerminalViewStateTest {
    @Test
    fun derivesPoweredOffConnectingAndActiveStates() {
        val snapshot = ScreenBufferSnapshot.empty(width = 10, height = 5, colour = true)

        assertIs<WorkbenchTerminalViewState.PoweredOff>(
            WorkbenchTerminalViewState.from(isComputerOn = false, snapshot = null),
        )
        assertIs<WorkbenchTerminalViewState.Connecting>(
            WorkbenchTerminalViewState.from(isComputerOn = true, snapshot = null),
        )
        assertIs<WorkbenchTerminalViewState.Active>(
            WorkbenchTerminalViewState.from(isComputerOn = true, snapshot = snapshot),
        )
    }
}
