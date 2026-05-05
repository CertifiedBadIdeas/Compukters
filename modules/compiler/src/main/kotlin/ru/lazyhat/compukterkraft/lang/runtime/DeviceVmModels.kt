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

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayInfo
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat

enum class DeviceCapability {
    TERMINAL,
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
    val instructionsPerSlice: Int = 64,
    val wallTimeGuardNanosPerSlice: Long,
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
    val cpuBudgetNanosPerSlice: Long,
    val maxEventQueueSize: Int,
    val terminalWidth: Int,
    val terminalHeight: Int,
    val colorTerminal: Boolean,
    val allowedCapabilities: Set<DeviceCapability> = DeviceCapability.entries.toSet(),
    val bootScriptName: String = DeviceProgramFiles.BIOS_SCRIPT_NAME,
    val resources: DeviceResources =
        DeviceResources(
            cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = cpuBudgetNanosPerSlice),
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

    /** True when the VM is in a terminal state ([Stopped] or [Crashed]). */
    val isTerminal: Boolean get() = this is Stopped || this is Crashed

    /** True when the VM is actively executing ([Running], [WaitingEvent], [Sleeping], [Booting]). */
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

data class VmSnapshot(
    val deviceId: Int,
    val profile: DeviceProfile,
    val state: VmState,
    val currentTick: Long,
    val queuedEvents: Int,
    val pendingHostCalls: Int,
)

sealed interface HostCall {
    val id: Long

    data class FileExists(
        override val id: Long,
        val path: String,
    ) : HostCall

    data class FileIsDirectory(
        override val id: Long,
        val path: String,
    ) : HostCall

    data class FileReadText(
        override val id: Long,
        val path: String,
    ) : HostCall

    data class FileWriteText(
        override val id: Long,
        val path: String,
        val text: String,
    ) : HostCall

    data class FileMakeDirectory(
        override val id: Long,
        val path: String,
    ) : HostCall

    data class FileRemove(
        override val id: Long,
        val path: String,
    ) : HostCall

    data class FileList(
        override val id: Long,
        val path: String,
    ) : HostCall
}

sealed interface HostResult {
    val id: Long

    data class Success(
        override val id: Long,
        val value: Any? = null,
    ) : HostResult

    data class Failure(
        override val id: Long,
        val message: String,
    ) : HostResult
}

interface DeviceVmHandle : AutoCloseable {
    val deviceId: Int
    val profile: DeviceProfile

    fun boot(): Boolean

    fun stop(reason: VmStopReason = VmStopReason.REQUESTED)

    fun enqueueEvent(event: VmEvent): Boolean

    fun requestSlice(serverTick: Long)

    fun drainHostCalls(): List<HostCall>

    fun deliverHostResults(results: List<HostResult>)

    fun snapshot(): VmSnapshot

    /**
     * Take an immutable snapshot of the VM's screen buffer if it has changed since the last call.
     * Returns `null` when the screen has not been modified.
     */
    fun readScreenSnapshot(): ScreenBufferSnapshot?

    /**
     * Force a screen snapshot regardless of dirty state (e.g. when a new player opens the GUI).
     */
    fun forceScreenSnapshot(): ScreenBufferSnapshot

    fun attachDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
    ): DisplayInfo = DisplayInfo(displayId, width, height, pixelFormat)

    fun resizeDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
    ): DisplayInfo = attachDisplay(displayId, width, height, pixelFormat)

    fun detachDisplay(displayId: Int) = Unit

    fun drainDisplayFrames(): List<DisplayFrameDelta> = emptyList()

    override fun close() = stop(VmStopReason.CLOSED)
}

interface VmSupervisor : AutoCloseable {
    fun get(deviceId: Int): DeviceVmHandle?

    fun remove(
        deviceId: Int,
        reason: VmStopReason = VmStopReason.CLOSED,
    )
}
