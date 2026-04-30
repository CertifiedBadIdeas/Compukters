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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVm
import ru.lazyhat.compukterkraft.core.device.vm.DeviceVmLogger
import ru.lazyhat.compukterkraft.core.device.vm.DeviceVmSupervisor
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIdeHost
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import java.io.Closeable

/**
 * Unified registry that owns both the [RuntimeDevice] instances and the
 * underlying VM handles managed by [DeviceVmSupervisor].
 *
 * Provides a single entry point for runtime-device and VM lifecycle management.
 */
class DeviceManager(
    private val vmSupervisor: DeviceVmSupervisor,
) : Closeable {
    private val devices: MutableMap<Int, RuntimeDevice> = HashMap()

    // ── Workspace / IDE access (delegated to supervisor) ────────────

    val workspace: DeviceWorkspace get() = vmSupervisor.workspace
    val ide: DeviceIdeHost get() = vmSupervisor.ide

    fun ensureWorkspaceInitialized(deviceId: Int) = vmSupervisor.ensureWorkspaceInitialized(deviceId)

    // ── RuntimeDevice registry ──────────────────────────────────────

    fun get(deviceId: Int): RuntimeDevice? = devices[deviceId]

    fun add(device: RuntimeDevice) {
        check(!devices.containsKey(device.deviceId)) {
            "Device with ${device.deviceId} already exists!"
        }
        devices.put(device.deviceId, device)
    }

    fun remove(deviceId: Int): RuntimeDevice? = devices.remove(deviceId)

    // ── VM handle management (delegated to supervisor) ──────────────

    fun getOrCreateVm(
        deviceId: Int,
        profile: DeviceProfile,
        labelProvider: () -> String?,
        logger: DeviceVmLogger,
    ): BackgroundDeviceVm = vmSupervisor.getOrCreate(deviceId, profile, labelProvider, logger)

    fun removeVm(
        deviceId: Int,
        reason: VmStopReason = VmStopReason.CLOSED,
    ) = vmSupervisor.remove(deviceId, reason)

    // ── Lifecycle ───────────────────────────────────────────────────

    override fun close() {
        devices.values.forEach { it.close() }
        devices.clear()
        vmSupervisor.close()
    }
}
