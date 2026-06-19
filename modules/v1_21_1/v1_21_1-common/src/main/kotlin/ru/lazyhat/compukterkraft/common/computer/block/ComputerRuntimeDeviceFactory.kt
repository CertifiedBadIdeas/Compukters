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
import ru.lazyhat.compukterkraft.core.device.runtime.K16RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.vm.DeviceProfileRegistry
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.storage.FileK16VolumeStore
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16RuntimeSnapshotStore
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16VolumeBlob
import java.nio.file.Path

object ComputerRuntimeDeviceFactory {
    fun createK16Computer(
        level: ServerLevel,
        tile: AbstractComputerBlockEntity,
        deviceId: Int,
    ): RuntimeDevice {
        val host = BlockEntityRuntimeDeviceHost(level, tile)
        val worldRoot = level.server.getWorldPath(LevelResource.ROOT)
        val volumeStore = FileK16VolumeStore(worldRoot)
        val snapshotStore = K16RuntimeSnapshotStore(worldRoot)
        val workspace = worldRoot.resolve("compukterkraft").resolve("computers").resolve(deviceId.toString())
        return K16RuntimeDevice(
            deviceId = deviceId,
            properties = DeviceProperties(tile.family, tile.label),
            endpointFactory = {
                val profile = DeviceProfileRegistry.forFamily(tile.family)
                val memorySize = k16MemorySizeBytes(profile.resources.memory.vmRamBytes)
                val maxSteps = k16MaxSteps(profile.resources.cpu.wallTimeGuardNanosPerSlice)
                K16SystemVolumeWorkspace.prepareStorage0Volume(workspace)
                val storage0 = volumeStore.openOrCreateComputerVolume(deviceId, "storage0")
                val biosFlashPath = K16BiosFlashWorkspace.prepareBiosFlash(workspace)
                val snapshot =
                    tile.consumePendingRuntimeSnapshot()
                        ?: snapshotStore.readComputerSnapshotOrNull(deviceId)
                createK16ComputerEndpoint(biosFlashPath, storage0, snapshot, memorySize, maxSteps)
            },
            stateSink = host.stateSink,
            displayNetwork = host.displayNetwork,
        )
    }

    private fun k16MemorySizeBytes(vmRamBytes: Long): Int {
        require(vmRamBytes in 1..Int.MAX_VALUE.toLong()) {
            "K16 VM RAM size must fit in signed 32-bit bytes: $vmRamBytes"
        }
        return vmRamBytes.toInt()
    }

    private fun k16MaxSteps(wallTimeGuardNanosPerSlice: Long): Long {
        require(wallTimeGuardNanosPerSlice > 0) {
            "K16 VM max steps must be positive: $wallTimeGuardNanosPerSlice"
        }
        return wallTimeGuardNanosPerSlice
    }

    private fun createK16ComputerEndpoint(
        biosFlashPath: Path,
        storage0: K16VolumeBlob,
        snapshot: ByteArray?,
        memorySize: Int,
        maxSteps: Long,
    ) =
        try {
            val storage0Path = storage0.path
            storage0.close()
            if (snapshot == null) {
                K16ComputerRuntimeFactory.createFromBiosFlash(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                    memorySize = memorySize,
                    maxSteps = maxSteps,
                )
            } else {
                K16ComputerRuntimeFactory.restoreFromBiosFlashSnapshot(
                    biosFlashPath = biosFlashPath,
                    storage0Path = storage0Path,
                    snapshot = snapshot,
                    memorySize = memorySize,
                )
            }
        } catch (error: Throwable) {
            storage0.close()
            throw error
        }
}
