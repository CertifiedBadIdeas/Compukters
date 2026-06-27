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

package ru.lazyhat.compukterkraft.lang.runtime

enum class DeviceCapability {
    DISPLAY,
    FILESYSTEM,
    EVENTS,
    SYSTEM,
    IPC,
    REDSTONE,
    PERIPHERALS,
    IDE,
}

data class DeviceCpuResources(
    val maxStepsPerSlice: Long,
    val maxTurnsPerTick: Int,
)

data class DeviceMemoryResources(
    val vmRamBytes: Long = Long.MAX_VALUE,
)

data class DeviceStorageResources(
    val programRomBytes: Long = Long.MAX_VALUE,
    val diskBytes: Long = Long.MAX_VALUE,
)

data class DeviceQueueResources(
    val eventQueueSlots: Int,
    val hostCallQueueSlots: Int = eventQueueSlots,
    val ipcChannelBytes: Int = 16 * 1024,
)

data class DeviceResources(
    val cpu: DeviceCpuResources,
    val memory: DeviceMemoryResources = DeviceMemoryResources(),
    val storage: DeviceStorageResources = DeviceStorageResources(),
    val queues: DeviceQueueResources,
)

data class DeviceProfile(
    val id: String,
    val displayName: String,
    val maxStepsPerSlice: Long,
    val maxEventQueueSize: Int,
    val allowedCapabilities: Set<DeviceCapability> = DeviceCapability.entries.toSet(),
    val resources: DeviceResources =
        DeviceResources(
            cpu = DeviceCpuResources(maxStepsPerSlice = maxStepsPerSlice, maxTurnsPerTick = 8),
            queues = DeviceQueueResources(eventQueueSlots = maxEventQueueSize),
        ),
)

sealed interface VmState {
    data object Cold : VmState

    data object Booting : VmState

    data object Running : VmState

    data object WaitingEvent : VmState

    data object Sleeping : VmState

    data class Stopped(
        val reason: VmStopReason,
    ) : VmState

    data class Crashed(
        val errorMessage: String?,
    ) : VmState

    val isTerminal: Boolean get() = this is Stopped || this is Crashed

    val isActive: Boolean get() = !isTerminal && this !is Cold
}

enum class VmStopReason {
    REQUESTED,
    REBOOT,
    CLOSED,
}

data class VmEvent(
    val name: String,
    val arguments: List<Any?> = emptyList(),
)
