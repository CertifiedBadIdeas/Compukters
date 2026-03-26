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

package ru.lazyhat.compukterkraft.computer.vm

import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import kotlinx.coroutines.asCoroutineDispatcher
import ru.lazyhat.compukterkraft.MOD_ID
import ru.lazyhat.compukterkraft.machine.ComputerIdeHost
import ru.lazyhat.compukterkraft.machine.ComputerProfile
import ru.lazyhat.compukterkraft.machine.ComputerVmHandle
import ru.lazyhat.compukterkraft.machine.ComputerWorkspace
import ru.lazyhat.compukterkraft.machine.VmStopReason
import ru.lazyhat.compukterkraft.machine.VmSupervisor
import ru.lazyhat.compukterkraft.language.LanguageServices
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ComputerVmSupervisor(
    server: MinecraftServer,
) : VmSupervisor,
    Closeable {
    private val executor = Executors.newFixedThreadPool(2)
    private val dispatcher = executor.asCoroutineDispatcher()
    private val handles = ConcurrentHashMap<Int, ComputerVmHandle>()
    private val workspaceStore =
        FileComputerWorkspace(
            rootPath = server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID).resolve("computers"),
            bundledScriptLoader = LanguageServices::bundledScript,
        )
    private val ideHost = EnvironmentComputerIdeHost(workspaceStore)

    val workspace: ComputerWorkspace
        get() = workspaceStore

    val ide: ComputerIdeHost
        get() = ideHost

    fun ensureWorkspaceInitialized(computerId: Int) {
        workspaceStore.ensureInitialized(computerId)
    }

    fun getOrCreate(
        computerId: Int,
        profile: ComputerProfile,
        callbacks: ComputerVmCallbacks,
        logger: ComputerVmLogger,
    ): ComputerVmHandle =
        handles.computeIfAbsent(computerId) {
            BackgroundComputerVm(
                computerId = computerId,
                profile = profile,
                dispatcher = dispatcher,
                callbacks = callbacks,
                logger = logger,
            )
        }

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
