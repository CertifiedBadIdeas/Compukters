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

import java.util.ArrayDeque
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
    private val runnableLock = Any()
    private val runnableQueue = ArrayDeque<Int>()
    private val runnablePids = mutableSetOf<Int>()

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
        enqueueRunnable(pid)
    }

    override fun markRunnable(pid: Int) {
        if (updateState(pid, VmProcessState.Runnable)) {
            enqueueRunnable(pid)
        }
    }

    override fun markWaitingEvent(
        pid: Int,
        filter: String?,
    ) {
        if (updateState(pid, VmProcessState.WaitingEvent(filter))) {
            removeRunnable(pid)
        }
    }

    override fun markWaitingIpc(
        pid: Int,
        channelId: Int,
    ) {
        if (updateState(pid, VmProcessState.WaitingIpc(channelId))) {
            removeRunnable(pid)
        }
    }

    override fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    ) {
        if (updateState(pid, VmProcessState.WaitingProcess(targetPid))) {
            removeRunnable(pid)
        }
    }

    override fun markSleeping(
        pid: Int,
        untilTick: Long,
    ) {
        if (updateState(pid, VmProcessState.Sleeping(untilTick))) {
            removeRunnable(pid)
        }
    }

    override fun markExited(
        pid: Int,
        exitCode: Int,
    ) {
        if (updateState(pid, VmProcessState.Exited(exitCode))) {
            removeRunnable(pid)
        }
    }

    override fun markCrashed(
        pid: Int,
        message: String,
    ) {
        if (updateState(pid, VmProcessState.Crashed(message))) {
            removeRunnable(pid)
        }
    }

    fun snapshot(pid: Int): VmProcessRecord? = records[pid]

    fun snapshot(): List<VmProcessRecord> = records.values.sortedBy { it.pid }

    fun runnableSnapshot(): List<Int> =
        synchronized(runnableLock) {
            runnableQueue.toList()
        }

    fun nextRunnablePid(): Int? =
        synchronized(runnableLock) {
            while (runnableQueue.isNotEmpty()) {
                val pid = runnableQueue.removeFirst()
                runnablePids.remove(pid)
                if (records[pid]?.state == VmProcessState.Runnable) {
                    enqueueRunnableLocked(pid)
                    return@synchronized pid
                }
            }
            null
        }

    private fun updateState(
        pid: Int,
        state: VmProcessState,
    ): Boolean {
        var updated = false
        records.computeIfPresent(pid) { _, record ->
            updated = true
            record.copy(state = state)
        }
        return updated
    }

    private fun enqueueRunnable(pid: Int) {
        synchronized(runnableLock) {
            enqueueRunnableLocked(pid)
        }
    }

    private fun enqueueRunnableLocked(pid: Int) {
        if (records[pid]?.state == VmProcessState.Runnable && runnablePids.add(pid)) {
            runnableQueue.addLast(pid)
        }
    }

    private fun removeRunnable(pid: Int) {
        synchronized(runnableLock) {
            if (runnablePids.remove(pid)) {
                runnableQueue.remove(pid)
            }
        }
    }
}
