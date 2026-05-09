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

import kotlinx.coroutines.asCoroutineDispatcher
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.core.device.runtime.NoOpRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.platform.api.ServerWorldAccess
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIdeHost
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceVmHandle
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import ru.lazyhat.compukterkraft.lang.runtime.VmSupervisor
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DeviceVmSupervisor(
    serverWorldAccess: ServerWorldAccess,
) : VmSupervisor,
    Closeable {
    private val executor = Executors.newFixedThreadPool(2)
    private val dispatcher = executor.asCoroutineDispatcher()
    private val handles = ConcurrentHashMap<Int, DeviceVmHandle>()
    private val computersPath = serverWorldAccess.getWorldSavePath().resolve(MOD_ID).resolve("computers")
    private val workspaceInitializer = DeviceWorkspaceInitializer(computersPath)
    private val workspaceStore = DeviceWorkspaceHost(rootPath = computersPath, defaultDiskQuotaBytes = Config.computerSpaceLimit.toLong())
    private val ideHost = WorkspaceDeviceIdeHost(workspaceStore)

    val workspace: DeviceWorkspace
        get() = workspaceStore

    val ide: DeviceIdeHost
        get() = ideHost

    fun ensureWorkspaceInitialized(deviceId: Int) {
        workspaceInitializer.ensureInitialized(deviceId)
    }

    fun getOrCreate(
        deviceId: Int,
        profile: DeviceProfile,
        labelProvider: () -> String?,
        logger: DeviceVmLogger,
        runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
    ): BackgroundDeviceVm =
        handles.computeIfAbsent(deviceId) {
            workspaceStore.setDiskQuota(deviceId, profile.resources.storage.diskBytes)
            BackgroundDeviceVm(
                deviceId = deviceId,
                profile = profile,
                dispatcher = dispatcher,
                labelProvider = labelProvider,
                logger = logger,
                workspace = workspaceStore,
                runtimeMetricsCollector = runtimeMetricsCollector,
                nativeFilesystemRoot = workspaceStore.computerRoot(deviceId),
            )
        } as BackgroundDeviceVm

    override fun get(deviceId: Int): DeviceVmHandle? = handles[deviceId]

    override fun remove(
        deviceId: Int,
        reason: VmStopReason,
    ) {
        handles.remove(deviceId)?.stop(reason)
    }

    override fun close() {
        handles.values.forEach { it.close() }
        handles.clear()
        dispatcher.close()
        executor.shutdownNow()
    }
}
