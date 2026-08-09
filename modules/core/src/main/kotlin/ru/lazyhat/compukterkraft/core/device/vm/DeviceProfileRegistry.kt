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

package ru.lazyhat.compukterkraft.core.device.vm

import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources

object DeviceProfileRegistry {
    const val NORMAL_NOTEBOOK_RAM_BYTES: Int = 1 * 1024 * 1024
    const val ADVANCED_NOTEBOOK_RAM_BYTES: Int = 4 * 1024 * 1024

    fun forFamily(family: DeviceFamily): DeviceProfile =
        when (family) {
            DeviceFamily.NORMAL -> {
                DeviceProfile(
                    id = "normal",
                    displayName = "Normal Notebook",
                    maxStepsPerSlice = 1_000_000,
                    maxEventQueueSize = 32,
                    allowedCapabilities = defaultCapabilities(),
                    resources =
                        defaultResources(
                            maxStepsPerSlice = 1_000_000,
                            maxTurnsPerTick = 32,
                            eventQueueSlots = 32,
                            vmRamBytes = NORMAL_NOTEBOOK_RAM_BYTES.toLong(),
                            diskBytes = Config.computerSpaceLimit.toLong(),
                        ),
                )
            }

            DeviceFamily.ADVANCED -> {
                DeviceProfile(
                    id = "advanced",
                    displayName = "Advanced Notebook",
                    maxStepsPerSlice = 2_000_000,
                    maxEventQueueSize = 64,
                    allowedCapabilities = defaultCapabilities(),
                    resources =
                        defaultResources(
                            maxStepsPerSlice = 2_000_000,
                            maxTurnsPerTick = 32,
                            eventQueueSlots = 32,
                            vmRamBytes = ADVANCED_NOTEBOOK_RAM_BYTES.toLong(),
                            diskBytes = Config.computerSpaceLimit.toLong(),
                        ),
                )
            }

            DeviceFamily.COMMAND -> {
                DeviceProfile(
                    id = "command",
                    displayName = "Command Computer",
                    maxStepsPerSlice = 4_000_000,
                    maxEventQueueSize = 256,
                    allowedCapabilities = defaultCapabilities() + DeviceCapability.REDSTONE + DeviceCapability.PERIPHERALS,
                    resources =
                        defaultResources(
                            maxStepsPerSlice = 4_000_000,
                            maxTurnsPerTick = 32,
                            eventQueueSlots = 256,
                            vmRamBytes = Config.computerRamLimit.toLong(),
                            diskBytes = Config.computerSpaceLimit.toLong(),
                        ),
                )
            }
        }

    private fun defaultResources(
        maxStepsPerSlice: Long,
        maxTurnsPerTick: Int,
        eventQueueSlots: Int,
        vmRamBytes: Long,
        diskBytes: Long,
    ): DeviceResources =
        DeviceResources(
            cpu =
                DeviceCpuResources(
                    maxStepsPerSlice = maxStepsPerSlice,
                    maxTurnsPerTick = maxTurnsPerTick,
                ),
            memory = DeviceMemoryResources(vmRamBytes = vmRamBytes),
            storage = DeviceStorageResources(diskBytes = diskBytes),
            queues = DeviceQueueResources(eventQueueSlots = eventQueueSlots, hostCallQueueSlots = eventQueueSlots),
        )

    private fun defaultCapabilities(): Set<DeviceCapability> =
        setOf(
            DeviceCapability.DISPLAY,
            DeviceCapability.FILESYSTEM,
            DeviceCapability.EVENTS,
            DeviceCapability.SYSTEM,
            DeviceCapability.IPC,
            DeviceCapability.IDE,
        )
}
