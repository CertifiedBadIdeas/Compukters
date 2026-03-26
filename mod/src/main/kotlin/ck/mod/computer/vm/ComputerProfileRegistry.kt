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

package ck.mod.computer.vm

import ck.lang.runtime.ComputerCapability
import ck.lang.runtime.ComputerProfile
import ck.mod.Config
import ck.mod.block.ComputerFamily

object ComputerProfileRegistry {
    fun forFamily(family: ComputerFamily): ComputerProfile =
        when (family) {
            ComputerFamily.NORMAL -> {
                ComputerProfile(
                    id = "normal",
                    displayName = "Normal Computer",
                    cpuBudgetNanosPerSlice = 1_000_000,
                    maxEventQueueSize = 64,
                    terminalWidth = Config.DEFAULT_COMPUTER_TERM_WIDTH,
                    terminalHeight = Config.DEFAULT_COMPUTER_TERM_HEIGHT,
                    colorTerminal = false,
                    allowedCapabilities = defaultCapabilities(),
                )
            }

            ComputerFamily.ADVANCED -> {
                ComputerProfile(
                    id = "advanced",
                    displayName = "Advanced Computer",
                    cpuBudgetNanosPerSlice = 2_000_000,
                    maxEventQueueSize = 128,
                    terminalWidth = Config.DEFAULT_COMPUTER_TERM_WIDTH,
                    terminalHeight = Config.DEFAULT_COMPUTER_TERM_HEIGHT,
                    colorTerminal = true,
                    allowedCapabilities = defaultCapabilities(),
                )
            }

            ComputerFamily.COMMAND -> {
                ComputerProfile(
                    id = "command",
                    displayName = "Command Computer",
                    cpuBudgetNanosPerSlice = 4_000_000,
                    maxEventQueueSize = 256,
                    terminalWidth = Config.DEFAULT_COMPUTER_TERM_WIDTH,
                    terminalHeight = Config.DEFAULT_COMPUTER_TERM_HEIGHT,
                    colorTerminal = true,
                    allowedCapabilities = defaultCapabilities() + ComputerCapability.REDSTONE + ComputerCapability.PERIPHERALS,
                )
            }
        }

    private fun defaultCapabilities(): Set<ComputerCapability> =
        setOf(
            ComputerCapability.TERMINAL,
            ComputerCapability.FILESYSTEM,
            ComputerCapability.EVENTS,
            ComputerCapability.SYSTEM,
            ComputerCapability.IDE,
        )
}
