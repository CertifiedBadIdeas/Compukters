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
 */

package ru.lazyhat.compukters.impl.terminal

import kotlin.test.Test
import kotlin.test.assertNotNull

class TerminalScreenFocusTest {
    @Test
    fun `terminal screen owns initial focus policy`() {
        val initialFocus =
            TerminalScreen::class.java.declaredMethods.singleOrNull {
                it.name == "setInitialFocus" && it.parameterCount == 0
            }

        assertNotNull(initialFocus, "TerminalScreen must override Screen's automatic initial widget focus")
    }

    @Test
    fun `terminal screen owns mouse interaction focus policy`() {
        val mouseClicked =
            TerminalScreen::class.java.declaredMethods.singleOrNull {
                it.name == "mouseClicked" && it.parameterCount == 2
            }

        assertNotNull(mouseClicked, "TerminalScreen must clear widget focus after mouse interaction")
    }
}
