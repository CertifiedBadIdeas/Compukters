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

import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.runtime.DeviceFileSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceDisplayApi
import ru.lazyhat.compukterkraft.lang.runtime.DevicePeripheralApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProcessApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRedstoneApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStdioApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceTerminalApi
import ru.lazyhat.compukterkraft.lang.runtime.NoopDeviceDisplayApi
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmState

class VmRuntime(
    private val ctx: VmContext,
    private val initialProfile: DeviceProfile,
    val runtimeRegistry: BuiltinRegistry,
    private val systemApi: DeviceSystemApi,
    private val terminalApi: DeviceTerminalApi,
    private val displayApi: DeviceDisplayApi = NoopDeviceDisplayApi,
    private val stdioApi: DeviceStdioApi,
    private val filesystemApi: DeviceFileSystemApi,
    private val processApi: DeviceProcessApi,
    private val redstoneApi: DeviceRedstoneApi = object : DeviceRedstoneApi {},
    private val peripheralsApi: DevicePeripheralApi = object : DevicePeripheralApi {},
) : DeviceRuntime {
    override val profile: DeviceProfile = initialProfile
    override val system: DeviceSystemApi = systemApi
    override val terminal: DeviceTerminalApi = terminalApi
    override val display: DeviceDisplayApi = displayApi
    override val stdio: DeviceStdioApi = stdioApi
    override val filesystem: DeviceFileSystemApi = filesystemApi
    override val process: DeviceProcessApi = processApi
    override val redstone: DeviceRedstoneApi = redstoneApi
    override val peripherals: DevicePeripheralApi = peripheralsApi

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
