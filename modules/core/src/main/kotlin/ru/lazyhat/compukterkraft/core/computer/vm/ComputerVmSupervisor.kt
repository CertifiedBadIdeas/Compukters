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

package ru.lazyhat.compukterkraft.core.computer.vm

import kotlinx.coroutines.asCoroutineDispatcher
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.core.platform.api.ServerWorldAccess
import ru.lazyhat.compukterkraft.lang.runtime.ComputerIdeHost
import ru.lazyhat.compukterkraft.lang.runtime.ComputerProfile
import ru.lazyhat.compukterkraft.lang.runtime.ComputerVmHandle
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.VmStopReason
import ru.lazyhat.compukterkraft.lang.runtime.VmSupervisor
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ComputerVmSupervisor(
    serverWorldAccess: ServerWorldAccess,
) : VmSupervisor,
    Closeable {
    private val executor = Executors.newFixedThreadPool(2)
    private val dispatcher = executor.asCoroutineDispatcher()
    private val handles = ConcurrentHashMap<Int, ComputerVmHandle>()
    private val computersPath = serverWorldAccess.getWorldSavePath().resolve(MOD_ID).resolve("computers")
    private val workspaceInitializer = ComputerWorkspaceInitializer(computersPath)
    private val workspaceStore = ComputerWorkspaceHost(rootPath = computersPath, defaultDiskQuotaBytes = Config.computerSpaceLimit.toLong())
    private val ideHost = WorkspaceComputerIdeHost(workspaceStore)

    val workspace: ComputerWorkspace
        get() = workspaceStore

    val ide: ComputerIdeHost
        get() = ideHost

    fun ensureWorkspaceInitialized(computerId: Int) {
        workspaceInitializer.ensureInitialized(computerId)
    }

    fun getOrCreate(
        computerId: Int,
        profile: ComputerProfile,
        labelProvider: () -> String?,
        logger: ComputerVmLogger,
    ): BackgroundComputerVm =
        handles.computeIfAbsent(computerId) {
            workspaceStore.setDiskQuota(computerId, profile.resources.storage.diskBytes)
            BackgroundComputerVm(
                computerId = computerId,
                profile = profile,
                dispatcher = dispatcher,
                labelProvider = labelProvider,
                logger = logger,
                workspace = workspaceStore,
            )
        } as BackgroundComputerVm

    override fun get(computerId: Int): ComputerVmHandle? = handles[computerId]

    override fun remove(
        computerId: Int,
        reason: VmStopReason,
    ) {
        handles.remove(computerId)?.stop(reason)
    }

    override fun close() {
        handles.values.forEach { it.close() }
        handles.clear()
        dispatcher.close()
        executor.shutdownNow()
    }
}
