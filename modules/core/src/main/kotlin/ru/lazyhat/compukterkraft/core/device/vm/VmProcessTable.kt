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

import java.util.concurrent.ConcurrentHashMap

internal sealed interface VmProcessState {
    data object Runnable : VmProcessState

    data class WaitingEvent(
        val filter: String?,
    ) : VmProcessState

    data class WaitingIpc(
        val channelId: Int,
    ) : VmProcessState

    data class WaitingProcess(
        val targetPid: Int,
    ) : VmProcessState

    data class Sleeping(
        val untilTick: Long,
    ) : VmProcessState

    data class Exited(
        val exitCode: Int,
    ) : VmProcessState

    data class Crashed(
        val message: String,
    ) : VmProcessState
}

internal interface VmProcessStateReporter {
    fun markRunnable(pid: Int)

    fun markWaitingEvent(
        pid: Int,
        filter: String?,
    )

    fun markWaitingIpc(
        pid: Int,
        channelId: Int,
    )

    fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    )

    fun markSleeping(
        pid: Int,
        untilTick: Long,
    )

    fun markExited(
        pid: Int,
        exitCode: Int,
    )

    fun markCrashed(
        pid: Int,
        message: String,
    )
}

internal object NoOpVmProcessStateReporter : VmProcessStateReporter {
    override fun markRunnable(pid: Int) = Unit

    override fun markWaitingEvent(
        pid: Int,
        filter: String?,
    ) = Unit

    override fun markWaitingIpc(
        pid: Int,
        channelId: Int,
    ) = Unit

    override fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    ) = Unit

    override fun markSleeping(
        pid: Int,
        untilTick: Long,
    ) = Unit

    override fun markExited(
        pid: Int,
        exitCode: Int,
    ) = Unit

    override fun markCrashed(
        pid: Int,
        message: String,
    ) = Unit
}

internal data class VmProcessRecord(
    val pid: Int,
    val parentPid: Int,
    val programPath: String,
    val argument: String,
    val workingDirectory: String,
    val state: VmProcessState,
)

internal class VmProcessTable : VmProcessStateReporter {
    private val records = ConcurrentHashMap<Int, VmProcessRecord>()

    fun registerProcess(
        pid: Int,
        parentPid: Int,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ) {
        records[pid] =
            VmProcessRecord(
                pid = pid,
                parentPid = parentPid,
                programPath = programPath,
                argument = argument,
                workingDirectory = workingDirectory,
                state = VmProcessState.Runnable,
            )
    }

    override fun markRunnable(pid: Int) {
        updateState(pid, VmProcessState.Runnable)
    }

    override fun markWaitingEvent(
        pid: Int,
        filter: String?,
    ) {
        updateState(pid, VmProcessState.WaitingEvent(filter))
    }

    override fun markWaitingIpc(
        pid: Int,
        channelId: Int,
    ) {
        updateState(pid, VmProcessState.WaitingIpc(channelId))
    }

    override fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    ) {
        updateState(pid, VmProcessState.WaitingProcess(targetPid))
    }

    override fun markSleeping(
        pid: Int,
        untilTick: Long,
    ) {
        updateState(pid, VmProcessState.Sleeping(untilTick))
    }

    override fun markExited(
        pid: Int,
        exitCode: Int,
    ) {
        updateState(pid, VmProcessState.Exited(exitCode))
    }

    override fun markCrashed(
        pid: Int,
        message: String,
    ) {
        updateState(pid, VmProcessState.Crashed(message))
    }

    fun snapshot(pid: Int): VmProcessRecord? = records[pid]

    fun snapshot(): List<VmProcessRecord> = records.values.sortedBy { it.pid }

    private fun updateState(
        pid: Int,
        state: VmProcessState,
    ) {
        records.computeIfPresent(pid) { _, record -> record.copy(state = state) }
    }
}
