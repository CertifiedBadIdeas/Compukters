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
