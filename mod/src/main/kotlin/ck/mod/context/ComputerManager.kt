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

package ck.mod.context

import ck.lang.runtime.ComputerIdeHost
import ck.lang.runtime.ComputerProfile
import ck.lang.runtime.ComputerVmHandle
import ck.lang.runtime.ComputerWorkspace
import ck.lang.runtime.VmStopReason
import ck.mod.computer.ServerComputer
import ck.mod.computer.vm.ComputerVmCallbacks
import ck.mod.computer.vm.ComputerVmLogger
import ck.mod.computer.vm.ComputerVmSupervisor
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.io.Closeable

/**
 * Unified registry that owns both the [ServerComputer] instances and the
 * underlying [ComputerVmHandle] objects.
 *
 * Replaces the old [ComputerRegistry] + [ComputerVmSupervisor] pair,
 * eliminating parallel maps for the same entity.
 */
class ComputerManager(
    private val vmSupervisor: ComputerVmSupervisor,
) : Closeable {
    private val computers: Int2ObjectMap<ServerComputer> = Int2ObjectOpenHashMap()

    // ── Workspace / IDE access (delegated to supervisor) ────────────

    val workspace: ComputerWorkspace get() = vmSupervisor.workspace
    val ide: ComputerIdeHost get() = vmSupervisor.ide

    fun ensureWorkspaceInitialized(computerId: Int) = vmSupervisor.ensureWorkspaceInitialized(computerId)

    // ── ServerComputer registry ─────────────────────────────────────

    fun get(instanceId: Int): ServerComputer? = computers[instanceId]

    fun add(serverComputer: ServerComputer) {
        check(!computers.containsKey(serverComputer.instanceID)) {
            "Computer with ${serverComputer.instanceID} already exists!"
        }
        computers.put(serverComputer.instanceID, serverComputer)
    }

    fun remove(instanceId: Int): ServerComputer? = computers.remove(instanceId)

    // ── VM handle management (delegated to supervisor) ──────────────

    fun getOrCreateVm(
        computerId: Int,
        profile: ComputerProfile,
        callbacks: ComputerVmCallbacks,
        logger: ComputerVmLogger,
    ): ComputerVmHandle = vmSupervisor.getOrCreate(computerId, profile, callbacks, logger)

    fun removeVm(
        computerId: Int,
        reason: VmStopReason = VmStopReason.CLOSED,
    ) = vmSupervisor.remove(computerId, reason)

    // ── Lifecycle ───────────────────────────────────────────────────

    override fun close() {
        computers.values.forEach { it.close() }
        computers.clear()
        vmSupervisor.close()
    }
}
