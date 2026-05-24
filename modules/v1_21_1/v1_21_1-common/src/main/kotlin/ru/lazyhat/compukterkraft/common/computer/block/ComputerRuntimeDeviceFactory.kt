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
import net.minecraft.world.level.storage.LevelResource
import ru.lazyhat.compukterkraft.common.computer.context.BlockEntityRuntimeDeviceHost
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RuxRuntimeDevice
import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.storage.FileRuxVolumeStore
import ru.lazyhat.compukterkraft.lang.runtime.storage.RuxVolumeBlob

object ComputerRuntimeDeviceFactory {
    fun createRuxComputer(
        level: ServerLevel,
        tile: AbstractComputerBlockEntity,
        deviceId: Int,
    ): RuntimeDevice {
        val host = BlockEntityRuntimeDeviceHost(level, tile)
        val volumeStore = FileRuxVolumeStore(level.server.getWorldPath(LevelResource.ROOT))
        return RuxRuntimeDevice(
            deviceId = deviceId,
            properties = DeviceProperties(tile.family, tile.label),
            endpointFactory = {
                val storage0 = volumeStore.openOrCreateComputerVolume(deviceId, "storage0")
                createRuxComputerEndpoint(storage0)
            },
            stateSink = host.stateSink,
            displayNetwork = host.displayNetwork,
        )
    }

    private fun createRuxComputerEndpoint(storage0: RuxVolumeBlob) =
        try {
            RuxComputerRuntimeFactory.createFromResource(
                storage0Media = storage0.readAll(),
                storage0Sink = { snapshot ->
                    storage0.resize(snapshot.size.toLong())
                    storage0.write(0, snapshot)
                    storage0.flush()
                    storage0.close()
                },
            )
        } catch (error: Throwable) {
            storage0.close()
            throw error
        }

    private fun RuxVolumeBlob.readAll(): ByteArray {
        require(size <= Int.MAX_VALUE) {
            "Rux storage0 volume is too large to attach to the current native runtime: $size bytes"
        }
        return read(0, size.toInt())
    }
}
