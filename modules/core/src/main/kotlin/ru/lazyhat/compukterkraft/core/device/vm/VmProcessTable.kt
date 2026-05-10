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

    data class WaitingProcess(
        val targetPid: Int,
    ) : VmProcessState

    data class Exited(
        val exitCode: Int,
    ) : VmProcessState
}

internal data class VmProcessRecord(
    val pid: Int,
    val parentPid: Int,
    val programPath: String,
    val argument: String,
    val workingDirectory: String,
    val state: VmProcessState,
)

internal class VmProcessTable {
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

    fun markRunnable(pid: Int) {
        updateState(pid, VmProcessState.Runnable)
    }

    fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    ) {
        updateState(pid, VmProcessState.WaitingProcess(targetPid))
    }

    fun markExited(
        pid: Int,
        exitCode: Int,
    ) {
        updateState(pid, VmProcessState.Exited(exitCode))
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
