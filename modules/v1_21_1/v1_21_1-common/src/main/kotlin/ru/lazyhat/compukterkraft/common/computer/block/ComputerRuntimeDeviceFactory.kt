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
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.RuxComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.storage.FileRuxVolumeStore
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.storage.RuxVolumeBlob
import java.nio.file.Path

object ComputerRuntimeDeviceFactory {
    fun createRuxComputer(
        level: ServerLevel,
        tile: AbstractComputerBlockEntity,
        deviceId: Int,
    ): RuntimeDevice {
        val host = BlockEntityRuntimeDeviceHost(level, tile)
        val worldRoot = level.server.getWorldPath(LevelResource.ROOT)
        val volumeStore = FileRuxVolumeStore(worldRoot)
        val workspace = worldRoot.resolve("compukterkraft").resolve("computers").resolve(deviceId.toString())
        return RuxRuntimeDevice(
            deviceId = deviceId,
            properties = DeviceProperties(tile.family, tile.label),
            endpointFactory = {
                K16SystemVolumeWorkspace.prepareStorage0Volume(workspace)
                val storage0 = volumeStore.openOrCreateComputerVolume(deviceId, "storage0")
                val biosFlashPath = K16BiosFlashWorkspace.prepareBiosFlash(workspace)
                val snapshot = tile.consumePendingRuntimeSnapshot()
                createRuxComputerEndpoint(biosFlashPath, storage0, snapshot)
            },
            stateSink = host.stateSink,
            displayNetwork = host.displayNetwork,
        )
    }

    private fun createRuxComputerEndpoint(
        biosFlashPath: Path,
        storage0: RuxVolumeBlob,
        snapshot: ByteArray?,
    ) =
        try {
            val storage0Path = storage0.path
            storage0.close()
            if (snapshot == null) {
                RuxComputerRuntimeFactory.createFromBiosFlash(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                )
            } else {
                RuxComputerRuntimeFactory.restoreFromBiosFlashSnapshot(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                    snapshot = snapshot,
                )
            }
        } catch (error: Throwable) {
            storage0.close()
            throw error
        }
}
