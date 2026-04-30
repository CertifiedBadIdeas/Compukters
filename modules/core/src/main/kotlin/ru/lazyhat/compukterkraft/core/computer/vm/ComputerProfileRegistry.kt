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
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources

object ComputerProfileRegistry {
    fun forFamily(family: ComputerFamily): DeviceProfile =
        when (family) {
            ComputerFamily.NORMAL -> {
                DeviceProfile(
                    id = "normal",
                    displayName = "Normal Computer",
                    cpuBudgetNanosPerSlice = 1_000_000,
                    maxEventQueueSize = 64,
                    terminalWidth = Config.DEFAULT_COMPUTER_TERM_WIDTH,
                    terminalHeight = Config.DEFAULT_COMPUTER_TERM_HEIGHT,
                    colorTerminal = false,
                    allowedCapabilities = defaultCapabilities(),
                    resources =
                        defaultResources(
                            instructionsPerSlice = 64,
                            wallTimeGuardNanosPerSlice = 1_000_000,
                            eventQueueSlots = 64,
                            diskBytes = Config.computerSpaceLimit.toLong(),
                        ),
                )
            }

            ComputerFamily.ADVANCED -> {
                DeviceProfile(
                    id = "advanced",
                    displayName = "Advanced Computer",
                    cpuBudgetNanosPerSlice = 2_000_000,
                    maxEventQueueSize = 128,
                    terminalWidth = Config.DEFAULT_COMPUTER_TERM_WIDTH,
                    terminalHeight = Config.DEFAULT_COMPUTER_TERM_HEIGHT,
                    colorTerminal = true,
                    allowedCapabilities = defaultCapabilities(),
                    resources =
                        defaultResources(
                            instructionsPerSlice = 128,
                            wallTimeGuardNanosPerSlice = 2_000_000,
                            eventQueueSlots = 128,
                            diskBytes = Config.computerSpaceLimit.toLong(),
                        ),
                )
            }

            ComputerFamily.COMMAND -> {
                DeviceProfile(
                    id = "command",
                    displayName = "Command Computer",
                    cpuBudgetNanosPerSlice = 4_000_000,
                    maxEventQueueSize = 256,
                    terminalWidth = Config.DEFAULT_COMPUTER_TERM_WIDTH,
                    terminalHeight = Config.DEFAULT_COMPUTER_TERM_HEIGHT,
                    colorTerminal = true,
                    allowedCapabilities = defaultCapabilities() + DeviceCapability.REDSTONE + DeviceCapability.PERIPHERALS,
                    resources =
                        defaultResources(
                            instructionsPerSlice = 256,
                            wallTimeGuardNanosPerSlice = 4_000_000,
                            eventQueueSlots = 256,
                            diskBytes = Config.computerSpaceLimit.toLong(),
                        ),
                )
            }
        }

    private fun defaultResources(
        instructionsPerSlice: Int,
        wallTimeGuardNanosPerSlice: Long,
        eventQueueSlots: Int,
        diskBytes: Long,
    ): DeviceResources =
        DeviceResources(
            cpu =
                DeviceCpuResources(
                    instructionsPerSlice = instructionsPerSlice,
                    wallTimeGuardNanosPerSlice = wallTimeGuardNanosPerSlice,
                ),
            memory = DeviceMemoryResources(),
            storage = DeviceStorageResources(diskBytes = diskBytes),
            queues = DeviceQueueResources(eventQueueSlots = eventQueueSlots, hostCallQueueSlots = eventQueueSlots),
        )

    private fun defaultCapabilities(): Set<DeviceCapability> =
        setOf(
            DeviceCapability.TERMINAL,
            DeviceCapability.FILESYSTEM,
            DeviceCapability.EVENTS,
            DeviceCapability.SYSTEM,
            DeviceCapability.IDE,
        )

    private const val NORMAL_COMPUTER_EXTRA_HEIGHT: Int = 2
}
