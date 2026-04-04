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

package ck.mod.computer.vm

import ck.lang.runtime.ComputerFileSystemApi
import ck.lang.runtime.ComputerPeripheralApi
import ck.lang.runtime.ComputerProcessApi
import ck.lang.runtime.ComputerProfile
import ck.lang.runtime.ComputerRedstoneApi
import ck.lang.runtime.ComputerRuntime
import ck.lang.runtime.ComputerSystemApi
import ck.lang.runtime.ComputerTerminalApi
import ck.lang.runtime.VmEvent
import ck.lang.runtime.VmState

class VmRuntime(
    private val ctx: VmContext,
    private val initialProfile: ComputerProfile,
    private val systemApi: ComputerSystemApi,
    private val terminalApi: ComputerTerminalApi,
    private val filesystemApi: ComputerFileSystemApi,
    private val processApi: ComputerProcessApi,
    private val redstoneApi: ComputerRedstoneApi = object : ComputerRedstoneApi {},
    private val peripheralsApi: ComputerPeripheralApi = object : ComputerPeripheralApi {},
) : ComputerRuntime {
    override val profile: ComputerProfile = initialProfile
    override val system: ComputerSystemApi = systemApi
    override val terminal: ComputerTerminalApi = terminalApi
    override val filesystem: ComputerFileSystemApi = filesystemApi
    override val process: ComputerProcessApi = processApi
    override val redstone: ComputerRedstoneApi = redstoneApi
    override val peripherals: ComputerPeripheralApi = peripheralsApi

    override suspend fun pullEvent(filter: String?): VmEvent {
        while (true) {
            ctx.setState(VmState.WaitingEvent)
            val event = ctx.receiveEvent()
            if (filter == null || event.name == filter) {
                ctx.setState(VmState.Running)
                ctx.schedulingPoint()
                return event
            }
        }
    }

    override suspend fun sleep(ticks: Long) {
        val targetTick = system.currentTick + ticks.coerceAtLeast(1)
        ctx.setSleepUntil(targetTick)
        while (system.currentTick < targetTick) {
            ctx.schedulingPoint()
        }
        ctx.setSleepUntil(null)
    }

    override suspend fun yield() {
        ctx.schedulingPoint()
    }
}
