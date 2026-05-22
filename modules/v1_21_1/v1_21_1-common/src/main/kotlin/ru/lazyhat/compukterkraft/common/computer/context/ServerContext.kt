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

package ru.lazyhat.compukterkraft.common.computer.context

import net.minecraft.server.MinecraftServer
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice

class ServerContext(
    val server: MinecraftServer,
) {
    private val devices: MutableMap<Int, RuntimeDevice> = HashMap()

    fun get(deviceId: Int): RuntimeDevice? = devices[deviceId]

    fun add(device: RuntimeDevice) {
        check(!devices.containsKey(device.deviceId)) {
            "Device with ${device.deviceId} already exists!"
        }
        devices[device.deviceId] = device
    }

    fun remove(deviceId: Int): RuntimeDevice? = devices.remove(deviceId)

    fun closeAll() {
        devices.values.forEach { it.close() }
        devices.clear()
    }

    companion object {
        private var current: ServerContext? = null

        val isInitialized: Boolean get() = current != null

        val server get() = context().server

        fun allocateDeviceId(): Int = ComputerIdentitySavedData.get(server).allocateComputerId()

        fun get(deviceId: Int): RuntimeDevice? = context().get(deviceId)

        fun add(device: RuntimeDevice) = context().add(device)

        fun remove(deviceId: Int): RuntimeDevice? = context().remove(deviceId)

        fun create(server: MinecraftServer) {
            check(current == null) { "ServerContext is already initialized" }
            current = ServerContext(server)
        }

        fun close() {
            current?.closeAll()
            current = null
        }

        private fun context(): ServerContext = checkNotNull(current) { "ServerContext has not been initialized" }
    }
}
