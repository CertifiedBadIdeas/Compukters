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

package ru.lazyhat.compukterkraft.core.computer.vm.api

import ru.lazyhat.compukterkraft.lang.runtime.ComputerPeripheralApi

data class VmPeripheralDevice(
    val id: String,
    val type: String,
    val label: String? = null,
    val side: String? = null,
)

class VmPeripheralRegistry {
    private val devices = linkedMapOf<String, VmPeripheralDevice>()

    fun attach(device: VmPeripheralDevice) {
        devices[device.id] = device
    }

    fun detach(id: String) {
        devices.remove(id)
    }

    fun devicesOfType(type: String): List<VmPeripheralDevice> = devices.values.filter { it.type == type }

    fun hasDevice(type: String): Boolean = devices.values.any { it.type == type }
}

class VmPeripheralRuntimeApi(
    private val registry: VmPeripheralRegistry,
) : ComputerPeripheralApi {
    override fun monitorExists(): Boolean = registry.hasDevice("monitor")
}