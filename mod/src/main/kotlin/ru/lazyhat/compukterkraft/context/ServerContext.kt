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

package ru.lazyhat.compukterkraft.context

import net.minecraft.server.MinecraftServer
import ru.lazyhat.compukterkraft.computer.vm.ComputerVmSupervisor
import ru.lazyhat.compukterkraft.utils.SingletonHolder

// private val LOGGER: Logger = LogManager.getLogger(ServerContext::class.java)

class ServerContext(
    val server: MinecraftServer,
) {
    val registry = ComputerRegistry()
    val vmSupervisor = ComputerVmSupervisor(server)

    companion object : SingletonHolder<ServerContext>() {
        val registry
            get() = instance.registry

        val vmSupervisor
            get() = instance.vmSupervisor

        val server
            get() = instance.server

        fun allocateComputerId(): Int = ComputerIdentitySavedData.get(server).allocateComputerId()

        fun create(server: MinecraftServer) {
            instance = ServerContext(server)
        }

        fun close() {
            instance.vmSupervisor.close()
            resetInstance()
        }
    }
}
