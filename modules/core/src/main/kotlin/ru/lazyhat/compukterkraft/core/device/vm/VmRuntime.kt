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
import ru.lazyhat.compukterkraft.lang.runtime.DeviceDisplayApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceEventApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceFileSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIpcApi
import ru.lazyhat.compukterkraft.lang.runtime.DevicePeripheralApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProcessApi
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRedstoneApi
import ru.lazyhat.compukterkraft.lang.runtime.NativeDeviceKernelProvider
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntime
import ru.lazyhat.compukterkraft.lang.runtime.DeviceRuntimeMetrics
import ru.lazyhat.compukterkraft.lang.runtime.DeviceSystemApi
import ru.lazyhat.compukterkraft.lang.runtime.NoopDeviceDisplayApi
import ru.lazyhat.compukterkraft.lang.runtime.NoopDeviceEventApi
import ru.lazyhat.compukterkraft.lang.runtime.NoopDeviceIpcApi
import ru.lazyhat.compukterkraft.lang.runtime.NoopDeviceRuntimeMetrics
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmState

class VmRuntime(
    private val ctx: VmContext,
    private val initialProfile: DeviceProfile,
    val runtimeRegistry: BuiltinRegistry,
    private val systemApi: DeviceSystemApi,
    private val displayApi: DeviceDisplayApi = NoopDeviceDisplayApi,
    private val filesystemApi: DeviceFileSystemApi,
    private val processApi: DeviceProcessApi,
    private val ipcApi: DeviceIpcApi = NoopDeviceIpcApi,
    private val eventApi: DeviceEventApi = NoopDeviceEventApi,
    private val redstoneApi: DeviceRedstoneApi = object : DeviceRedstoneApi {},
    private val peripheralsApi: DevicePeripheralApi = object : DevicePeripheralApi {},
    private val metricsApi: DeviceRuntimeMetrics = NoopDeviceRuntimeMetrics,
    override val nativeDeviceKernelHandle: Long = 0L,
) : DeviceRuntime, NativeDeviceKernelProvider {
    override val profile: DeviceProfile = initialProfile
    override val metrics: DeviceRuntimeMetrics = metricsApi
    override val system: DeviceSystemApi = systemApi
    override val display: DeviceDisplayApi = displayApi
    override val filesystem: DeviceFileSystemApi = filesystemApi
    override val process: DeviceProcessApi = processApi
    override val ipc: DeviceIpcApi = ipcApi
    override val events: DeviceEventApi = eventApi
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

    override suspend fun tryPullEvent(filter: String?): VmEvent? {
        val event = ctx.tryReceiveEvent()
        if (event == null) {
            ctx.schedulingPoint()
            return null
        }
        if (filter == null || event.name == filter) {
            ctx.schedulingPoint()
            return event
        }
        ctx.deferEvent(event)
        ctx.schedulingPoint()
        return null
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
