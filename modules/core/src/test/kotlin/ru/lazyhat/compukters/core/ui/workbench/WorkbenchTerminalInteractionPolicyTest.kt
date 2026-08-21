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

package ru.lazyhat.compukters.core.ui.workbench

import ru.lazyhat.compukters.core.workbench.WorkbenchMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkbenchTerminalInteractionPolicyTest {
    @Test
    fun hidesFocusHintWhenPoweredOff() {
        assertFalse(
            WorkbenchTerminalInteractionPolicy.showFocusHint(
                terminalState = WorkbenchTerminalViewState.PoweredOff,
                focused = false,
            ),
        )
        assertTrue(
            WorkbenchTerminalInteractionPolicy.showFocusHint(
                terminalState =
                    WorkbenchTerminalViewState.Active(
                        ru.lazyhat.compukters.lang.runtime.ScreenBufferSnapshot
                            .empty(width = 10, height = 5, colour = true),
                    ),
                focused = false,
            ),
        )
    }

    @Test
    fun blocksTerminalInputWhenPoweredOffOrNotInTerminalMode() {
        val activeState =
            WorkbenchTerminalViewState.Active(
                ru.lazyhat.compukters.lang.runtime.ScreenBufferSnapshot
                    .empty(width = 10, height = 5, colour = true),
            )

        assertFalse(
            WorkbenchTerminalInteractionPolicy.canAcceptInput(
                WorkbenchMode.TERMINAL,
                terminalState = WorkbenchTerminalViewState.PoweredOff,
                focused = true,
            ),
        )
        assertFalse(
            WorkbenchTerminalInteractionPolicy.canAcceptInput(
                WorkbenchMode.EDITOR,
                terminalState = activeState,
                focused = true,
            ),
        )
        assertTrue(
            WorkbenchTerminalInteractionPolicy.canAcceptInput(
                WorkbenchMode.TERMINAL,
                terminalState = activeState,
                focused = true,
            ),
        )
    }

    @Test
    fun connectingStateCannotAcceptInputOrShowHint() {
        assertFalse(
            WorkbenchTerminalInteractionPolicy.showFocusHint(
                terminalState = WorkbenchTerminalViewState.Connecting,
                focused = false,
            ),
        )
        assertFalse(
            WorkbenchTerminalInteractionPolicy.canAcceptInput(
                WorkbenchMode.TERMINAL,
                terminalState = WorkbenchTerminalViewState.Connecting,
                focused = true,
            ),
        )
    }
}
