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

package ru.lazyhat.compukterkraft.common.context

import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerVmSupervisor
import ru.lazyhat.compukterkraft.core.platform.api.ServerWorldAccess

class ServerContext(
    val server: MinecraftServer,
) {
    val vmSupervisor = ComputerVmSupervisor(ServerWorldAccess { server.getWorldPath(LevelResource.ROOT) })
    val computerManager = ComputerManager(vmSupervisor)

    companion object {
        private var current: ServerContext? = null

        val isInitialized: Boolean
            get() = current != null

        val vmSupervisor
            get() = context().vmSupervisor

        val computerManager
            get() = context().computerManager

        val server
            get() = context().server

        fun allocateComputerId(): Int = ComputerIdentitySavedData.get(server).allocateComputerId()

        fun create(server: MinecraftServer) {
            check(current == null) { "ServerContext is already initialized" }
            current = ServerContext(server)
        }

        fun close() {
            current?.computerManager?.close()
            current = null
        }

        private fun context(): ServerContext = checkNotNull(current) { "ServerContext has not been initialized" }
    }
}
