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
import ru.lazyhat.compukterkraft.core.device.runtime.K16RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.vm.DeviceProfileRegistry
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16StaticStorageAttachment
import ru.lazyhat.compukterkraft.lang.runtime.kraftos.K16SdkArtifacts
import ru.lazyhat.compukterkraft.lang.runtime.kraftos.KraftOsArtifactManifest
import ru.lazyhat.compukterkraft.lang.runtime.kraftos.KraftOsArtifacts
import ru.lazyhat.compukterkraft.lang.runtime.storage.FileK16VolumeStore
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16ImmutableArtifactWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16RuntimeSnapshotStore
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16VolumeBlob
import java.nio.file.Path

object ComputerRuntimeDeviceFactory {
    fun createK16Computer(
        level: ServerLevel,
        tile: AbstractComputerBlockEntity,
        deviceId: Int,
        moduleIdentity: () -> String?,
    ): RuntimeDevice {
        val host = BlockEntityRuntimeDeviceHost(level, tile)
        val worldRoot = level.server.getWorldPath(LevelResource.ROOT)
        val volumeStore = FileK16VolumeStore(worldRoot)
        val snapshotStore = K16RuntimeSnapshotStore(worldRoot)
        val workspace = worldRoot.resolve("compukterkraft").resolve("computers").resolve(deviceId.toString())
        val sdkArtifacts by lazy {
            K16SdkArtifacts(
                manifest = KraftOsArtifactManifest.load(),
                workspace = K16ImmutableArtifactWorkspace(worldRoot),
            )
        }
        var startupSnapshotAvailable = true
        return K16RuntimeDevice(
            deviceId = deviceId,
            properties = DeviceProperties(tile.family, tile.label),
            endpointFactory = {
                val snapshot =
                    if (startupSnapshotAvailable) {
                        startupSnapshotAvailable = false
                        tile.consumePendingRuntimeSnapshot()
                            ?: snapshotStore.readComputerSnapshotOrNull(deviceId)
                    } else {
                        null
                    }
                val storage1 =
                    moduleIdentity()?.let { identity ->
                        K16StaticStorageAttachment(sdkArtifacts.resolve(identity))
                    }
                val profile = DeviceProfileRegistry.forFamily(tile.family)
                val memorySize =
                    k16MemorySizeBytes(
                        vmRamBytes = profile.resources.memory.vmRamBytes,
                        minimumBootMemorySize = K16ComputerRuntimeFactory.MINIMUM_BOOT_MEMORY_SIZE,
                    )
                val maxSteps = k16MaxSteps(profile.resources.cpu.maxStepsPerSlice)
                val maxTurnsPerTick = k16MaxTurnsPerTick(profile.resources.cpu.maxTurnsPerTick)
                KraftOsArtifacts.prepareStorage0Volume(workspace)
                val storage0 = volumeStore.openOrCreateComputerVolume(deviceId, "storage0")
                val biosFlashPath = KraftOsArtifacts.prepareBiosFlash(workspace)
                createK16ComputerEndpoint(
                    biosFlashPath = biosFlashPath,
                    storage0 = storage0,
                    storage1 = storage1,
                    snapshot = snapshot,
                    memorySize = memorySize,
                    maxSteps = maxSteps,
                    maxTurnsPerTick = maxTurnsPerTick,
                )
            },
            stateSink = host.stateSink,
            serverThreadDispatcher = host.serverThreadDispatcher,
            displayNetwork = host.displayNetwork,
        )
    }

    private fun k16MemorySizeBytes(
        vmRamBytes: Long,
        minimumBootMemorySize: Int,
    ): Int {
        require(vmRamBytes in 1..Int.MAX_VALUE.toLong()) {
            "K16 VM RAM size must fit in signed 32-bit bytes: $vmRamBytes"
        }
        require(vmRamBytes >= minimumBootMemorySize) {
            "K16 VM RAM size is too small: $vmRamBytes bytes; minimum required is $minimumBootMemorySize bytes"
        }
        return vmRamBytes.toInt()
    }

    private fun k16MaxSteps(maxStepsPerSlice: Long): Long {
        require(maxStepsPerSlice > 0) {
            "K16 VM max steps must be positive: $maxStepsPerSlice"
        }
        return maxStepsPerSlice
    }

    private fun k16MaxTurnsPerTick(maxTurnsPerTick: Int): Int {
        require(maxTurnsPerTick > 0) {
            "K16 VM max turns per tick must be positive: $maxTurnsPerTick"
        }
        return maxTurnsPerTick
    }

    private fun createK16ComputerEndpoint(
        biosFlashPath: Path,
        storage0: K16VolumeBlob,
        storage1: K16StaticStorageAttachment?,
        snapshot: ByteArray?,
        memorySize: Int,
        maxSteps: Long,
        maxTurnsPerTick: Int,
    ) = try {
        val storage0Path = storage0.path
        storage0.close()
        if (snapshot == null) {
            K16ComputerRuntimeFactory.createFromBiosFlash(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
                storage1 = storage1,
                memorySize = memorySize,
                maxSteps = maxSteps,
                maxTurnsPerTick = maxTurnsPerTick,
            )
        } else {
            K16ComputerRuntimeFactory.restoreFromBiosFlashSnapshot(
                biosFlashPath = biosFlashPath,
                storage0Path = storage0Path,
                snapshot = snapshot,
                storage1 = storage1,
                memorySize = memorySize,
                maxTurnsPerTick = maxTurnsPerTick,
            )
        }
    } catch (error: Throwable) {
        storage0.close()
        throw error
    }
}
