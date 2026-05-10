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

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings

internal interface NativeProcessBridge {
    fun registerProcess(
        pid: Int,
        parentPid: Int,
        programPath: String,
    ): Boolean

    fun completeProcess(
        pid: Int,
        exitCode: Int,
    ): Boolean

    fun markRunnable(pid: Int): Boolean

    fun markWaitingEvent(
        pid: Int,
        filter: String?,
    ): Boolean

    fun markWaitingIpc(
        pid: Int,
        channelId: Int,
    ): Boolean

    fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    ): Boolean

    fun markSleeping(
        pid: Int,
        untilTick: Long,
    ): Boolean

    fun markCrashed(
        pid: Int,
        message: String,
    ): Boolean

    fun schedulerTick(currentTick: Long): VmProcessSchedulerTick?
}

internal object NoOpNativeProcessBridge : NativeProcessBridge {
    override fun registerProcess(
        pid: Int,
        parentPid: Int,
        programPath: String,
    ): Boolean = false

    override fun completeProcess(
        pid: Int,
        exitCode: Int,
    ): Boolean = false

    override fun markRunnable(pid: Int): Boolean = false

    override fun markWaitingEvent(
        pid: Int,
        filter: String?,
    ): Boolean = false

    override fun markWaitingIpc(
        pid: Int,
        channelId: Int,
    ): Boolean = false

    override fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    ): Boolean = false

    override fun markSleeping(
        pid: Int,
        untilTick: Long,
    ): Boolean = false

    override fun markCrashed(
        pid: Int,
        message: String,
    ): Boolean = false

    override fun schedulerTick(currentTick: Long): VmProcessSchedulerTick? = null
}

internal class NativeVmProcessBridge(
    private val kernelHandle: Long,
) : NativeProcessBridge {
    override fun registerProcess(
        pid: Int,
        parentPid: Int,
        programPath: String,
    ): Boolean =
        NativeVmBindings.registerProcess(
            kernelHandle = kernelHandle,
            pid = pid,
            parentPid = parentPid,
            programPath = programPath,
        )

    override fun completeProcess(
        pid: Int,
        exitCode: Int,
    ): Boolean =
        NativeVmBindings.completeProcess(
            kernelHandle = kernelHandle,
            pid = pid,
            exitCode = exitCode,
        )

    override fun markRunnable(pid: Int): Boolean =
        NativeVmBindings.markProcessRunnable(
            kernelHandle = kernelHandle,
            pid = pid,
        )

    override fun markWaitingEvent(
        pid: Int,
        filter: String?,
    ): Boolean =
        NativeVmBindings.markProcessWaitingForEvent(
            kernelHandle = kernelHandle,
            pid = pid,
            filter = filter,
        )

    override fun markWaitingIpc(
        pid: Int,
        channelId: Int,
    ): Boolean =
        NativeVmBindings.markProcessWaitingForIpc(
            kernelHandle = kernelHandle,
            pid = pid,
            channelId = channelId,
        )

    override fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    ): Boolean =
        NativeVmBindings.markProcessWaitingForProcess(
            kernelHandle = kernelHandle,
            pid = pid,
            targetPid = targetPid,
        )

    override fun markSleeping(
        pid: Int,
        untilTick: Long,
    ): Boolean =
        NativeVmBindings.markProcessSleeping(
            kernelHandle = kernelHandle,
            pid = pid,
            untilTick = untilTick,
        )

    override fun markCrashed(
        pid: Int,
        message: String,
    ): Boolean =
        NativeVmBindings.markProcessCrashed(
            kernelHandle = kernelHandle,
            pid = pid,
            message = message,
        )

    override fun schedulerTick(currentTick: Long): VmProcessSchedulerTick {
        val tick =
            NativeVmBindings.processSchedulerTick(
                kernelHandle = kernelHandle,
                currentTick = currentTick,
            )
        return VmProcessSchedulerTick(
            currentTick = tick.currentTick,
            wokenPids = tick.wokenPids,
            selectedPid = tick.selectedPid,
        )
    }
}
