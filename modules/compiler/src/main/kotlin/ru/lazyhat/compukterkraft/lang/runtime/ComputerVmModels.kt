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

enum class ComputerCapability {
    TERMINAL,
    FILESYSTEM,
    EVENTS,
    SYSTEM,
    REDSTONE,
    PERIPHERALS,
    IDE,
}

data class ComputerCpuResources(
    val instructionsPerSlice: Int = 64,
    val wallTimeGuardNanosPerSlice: Long,
)

data class ComputerMemoryResources(
    val vmRamBytes: Long = Long.MAX_VALUE,
)

data class ComputerStorageResources(
    val programRomBytes: Long = Long.MAX_VALUE,
    val diskBytes: Long = Long.MAX_VALUE,
)

data class ComputerQueueResources(
    val eventQueueSlots: Int,
    val hostCallQueueSlots: Int = eventQueueSlots,
)

data class ComputerResources(
    val cpu: ComputerCpuResources,
    val memory: ComputerMemoryResources = ComputerMemoryResources(),
    val storage: ComputerStorageResources = ComputerStorageResources(),
    val queues: ComputerQueueResources,
)

data class ComputerProfile(
    val id: String,
    val displayName: String,
    val cpuBudgetNanosPerSlice: Long,
    val maxEventQueueSize: Int,
    val terminalWidth: Int,
    val terminalHeight: Int,
    val colorTerminal: Boolean,
    val allowedCapabilities: Set<ComputerCapability> = ComputerCapability.entries.toSet(),
    val bootScriptName: String = ComputerProgramFiles.BIOS_SCRIPT_NAME,
    val resources: ComputerResources =
        ComputerResources(
            cpu = ComputerCpuResources(wallTimeGuardNanosPerSlice = cpuBudgetNanosPerSlice),
            queues = ComputerQueueResources(eventQueueSlots = maxEventQueueSize),
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
    val computerId: Int,
    val profile: ComputerProfile,
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

interface ComputerVmHandle : AutoCloseable {
    val computerId: Int
    val profile: ComputerProfile

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

    override fun close() = stop(VmStopReason.CLOSED)
}

interface VmSupervisor : AutoCloseable {
    fun get(computerId: Int): ComputerVmHandle?

    fun remove(
        computerId: Int,
        reason: VmStopReason = VmStopReason.CLOSED,
    )
}
