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

import ck.mod.computer.vm.ComputerVmSupervisor
import net.minecraft.server.MinecraftServer

class ServerContext(
    val server: MinecraftServer,
) {
    val registry = ComputerRegistry()
    val vmSupervisor = ComputerVmSupervisor(server)

    companion object {
        private var current: ServerContext? = null

        val isInitialized: Boolean
            get() = current != null

        val registry
            get() = context().registry

        val vmSupervisor
            get() = context().vmSupervisor

        val server
            get() = context().server

        fun allocateComputerId(): Int = ComputerIdentitySavedData.get(server).allocateComputerId()

        fun create(server: MinecraftServer) {
            check(current == null) { "ServerContext is already initialized" }
            current = ServerContext(server)
        }

        fun close() {
            current?.vmSupervisor?.close()
            current = null
        }

        private fun context(): ServerContext = checkNotNull(current) { "ServerContext has not been initialized" }
    }
}
