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

package ck.lang.runtime

enum class ComputerCapability {
    TERMINAL,
    FILESYSTEM,
    EVENTS,
    SYSTEM,
    REDSTONE,
    PERIPHERALS,
    IDE,
}

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
)

enum class VmState {
    COLD,
    BOOTING,
    RUNNING,
    WAITING_EVENT,
    SLEEPING,
    STOPPED,
    CRASHED,
}

enum class VmStopReason {
    REQUESTED,
    REBOOT,
    CLOSED,
    CRASHED,
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
    val stopReason: VmStopReason? = null,
    val errorMessage: String? = null,
)

sealed interface HostCall {
    val id: Long

    data class TerminalWrite(
        override val id: Long,
        val text: String,
        val newLine: Boolean = false,
    ) : HostCall

    data class TerminalClear(
        override val id: Long,
    ) : HostCall

    data class TerminalSetCursor(
        override val id: Long,
        val x: Int,
        val y: Int,
    ) : HostCall

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

    fun start(program: ComputerProgram): Boolean

    fun stop(reason: VmStopReason = VmStopReason.REQUESTED)

    fun enqueueEvent(event: VmEvent): Boolean

    fun requestSlice(serverTick: Long)

    fun drainHostCalls(): List<HostCall>

    fun deliverHostResults(results: List<HostResult>)

    fun snapshot(): VmSnapshot

    override fun close() = stop(VmStopReason.CLOSED)
}

interface VmSupervisor : AutoCloseable {
    fun get(computerId: Int): ComputerVmHandle?

    fun remove(
        computerId: Int,
        reason: VmStopReason = VmStopReason.CLOSED,
    )
}
