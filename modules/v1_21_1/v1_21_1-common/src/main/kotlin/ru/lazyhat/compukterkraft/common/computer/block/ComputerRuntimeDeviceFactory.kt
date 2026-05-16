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

package ru.lazyhat.compukterkraft.common.computer.block

import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukterkraft.common.computer.context.BlockEntityRuntimeDeviceHost
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RuxRuntimeDevice
import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxComputerRuntimeFactory

object ComputerRuntimeDeviceFactory {
    fun createRuxComputer(
        level: ServerLevel,
        tile: AbstractComputerBlockEntity,
        deviceId: Int,
    ): RuntimeDevice {
        val host = BlockEntityRuntimeDeviceHost(level, tile)
        return RuxRuntimeDevice(
            deviceId = deviceId,
            properties = DeviceProperties(tile.family, tile.label),
            endpointFactory = RuxComputerRuntimeFactory::createFromResource,
            stateSink = host.stateSink,
            displayNetwork = host.displayNetwork,
        )
    }
}
