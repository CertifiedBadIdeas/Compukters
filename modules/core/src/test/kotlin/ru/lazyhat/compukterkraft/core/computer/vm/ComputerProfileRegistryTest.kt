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

package ru.lazyhat.compukterkraft.core.computer.vm

import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerProfileRegistryTest {
    @Test
    fun normalComputerUsesTallerTerminalThanOtherFamilies() {
        val normal = ComputerProfileRegistry.forFamily(DeviceFamily.NORMAL)
        val advanced = ComputerProfileRegistry.forFamily(DeviceFamily.ADVANCED)
        val command = ComputerProfileRegistry.forFamily(DeviceFamily.COMMAND)

        assertEquals(Config.DEFAULT_COMPUTER_TERM_HEIGHT, normal.terminalHeight)
        assertEquals(Config.DEFAULT_COMPUTER_TERM_HEIGHT, advanced.terminalHeight)
        assertEquals(Config.DEFAULT_COMPUTER_TERM_HEIGHT, command.terminalHeight)
    }
}
