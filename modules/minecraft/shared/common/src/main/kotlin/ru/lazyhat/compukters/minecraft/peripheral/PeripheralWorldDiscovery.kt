/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerPeripheralIdentity
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

object PeripheralCableBlocks {
    private val blocks = CopyOnWriteArrayList<Supplier<out Block>>()

    @JvmStatic
    fun register(block: Supplier<out Block>) {
        blocks += block
    }

    internal fun contains(state: BlockState): Boolean = blocks.any { candidate -> state.block === candidate.get() }
}

object PeripheralCableTopologyCache {
    private val levels = WeakHashMap<ServerLevel, MutableMap<BlockPos, PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity>>>()

    @JvmStatic
    fun invalidate(level: Level) {
        if (level is ServerLevel) {
            check(level.server.isSameThread) { "peripheral cable cache must be invalidated on the server thread" }
            levels.remove(level)
        }
    }

    internal fun getOrCompute(
        level: ServerLevel,
        computerPosition: BlockPos,
        discover: () -> PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity>,
    ): PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity> {
        val cache = levels.getOrPut(level, ::linkedMapOf)
        val key = computerPosition.immutable()
        cache[key]?.let { return it }
        if (cache.size >= MAXIMUM_COMPUTER_ENTRIES) cache.clear()
        return discover().also { cache[key] = it }
    }

    private const val MAXIMUM_COMPUTER_ENTRIES = 1024
}

internal object PeripheralDeviceNames {
    fun resolveContact(
        level: ServerLevel,
        position: BlockPos,
        contactedFace: Direction,
    ): List<PeripheralDeviceIdentity> {
        check(level.server.isSameThread) { "peripheral names must be changed on the server thread" }
        val dimension = level.dimension().toString()
        return ComputerAddonHosts.resolvePeripheralContact(level, position, contactedFace).map { device ->
            PeripheralDeviceIdentity(device.providerId, dimension, device.anchor.immutable(), device.deviceKey)
        }
    }

    fun setName(
        level: ServerLevel,
        identity: PeripheralDeviceIdentity,
        name: String,
    ): String = PeripheralDeviceNameStorage.get(level).setName(identity, name)

    fun clearName(
        level: ServerLevel,
        identity: PeripheralDeviceIdentity,
    ): String? = PeripheralDeviceNameStorage.get(level).clearName(identity)
}

enum class ComputerPeripheralLookupStatus {
    FOUND,
    MISSING,
    AMBIGUOUS,
    INVALID_NAME,
    TOPOLOGY_LIMIT_EXCEEDED,
}

data class ComputerPeripheralLookupResult(
    val status: ComputerPeripheralLookupStatus,
    val identity: ComputerPeripheralIdentity? = null,
) {
    init {
        require((status == ComputerPeripheralLookupStatus.FOUND) == (identity != null)) {
            "only a found peripheral lookup may carry an identity"
        }
    }
}

object ComputerPeripheralLookup {
    @JvmStatic
    fun find(
        level: ServerLevel,
        computerPosition: BlockPos,
        providerId: String,
        requestedName: String,
    ): ComputerPeripheralLookupResult {
        val traversal = PeripheralWorldDiscovery.discover(level, computerPosition)
        return lookupComputerPeripheral(
            providerId,
            requestedName,
            traversal,
            PeripheralDeviceNameStorage.get(level).directory,
        )
    }
}

object ComputerPeripheralNames {
    @JvmStatic
    fun setName(
        level: ServerLevel,
        identity: ComputerPeripheralIdentity,
        requestedName: String,
    ): String =
        PeripheralDeviceNameStorage.get(level).setName(
            PeripheralDeviceIdentity(
                identity.providerId,
                level.dimension().toString(),
                identity.anchor.immutable(),
                identity.deviceKey,
            ),
            requestedName,
        )
}

internal fun lookupComputerPeripheral(
    providerId: String,
    requestedName: String,
    traversal: PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity>,
    directory: PeripheralDeviceDirectory,
): ComputerPeripheralLookupResult {
    val reachable =
        when (traversal) {
            is PeripheralCableTraversal.LimitExceeded -> {
                return ComputerPeripheralLookupResult(ComputerPeripheralLookupStatus.TOPOLOGY_LIMIT_EXCEEDED)
            }

            is PeripheralCableTraversal.Complete -> {
                traversal.contacts.filter { identity -> identity.providerId == providerId }
            }
        }
    val lookup =
        try {
            directory.lookup(requestedName, reachable)
        } catch (_: IllegalArgumentException) {
            return ComputerPeripheralLookupResult(ComputerPeripheralLookupStatus.INVALID_NAME)
        }
    return when (lookup) {
        PeripheralNameLookup.Missing -> {
            ComputerPeripheralLookupResult(ComputerPeripheralLookupStatus.MISSING)
        }

        is PeripheralNameLookup.Ambiguous -> {
            ComputerPeripheralLookupResult(ComputerPeripheralLookupStatus.AMBIGUOUS)
        }

        is PeripheralNameLookup.Found -> {
            ComputerPeripheralLookupResult(
                ComputerPeripheralLookupStatus.FOUND,
                ComputerPeripheralIdentity(
                    lookup.identity.providerId,
                    lookup.identity.anchor,
                    lookup.identity.deviceKey,
                ),
            )
        }
    }
}

internal object PeripheralWorldDiscovery {
    fun discover(
        level: ServerLevel,
        computerPosition: BlockPos,
    ): PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity> {
        check(level.server.isSameThread) { "peripheral cables must be discovered on the server thread" }
        return PeripheralCableTopologyCache.getOrCompute(level, computerPosition) {
            discoverUncached(level, adjacentCables(level, computerPosition))
        }
    }

    fun discoverFromDevice(
        level: ServerLevel,
        contactedPosition: BlockPos,
    ): PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity> {
        check(level.server.isSameThread) { "peripheral cables must be discovered on the server thread" }
        return PeripheralCableTopologyCache.getOrCompute(level, contactedPosition) {
            discoverUncached(level, adjacentCables(level, contactedPosition))
        }
    }

    fun discoverFromCable(
        level: ServerLevel,
        cablePosition: BlockPos,
    ): PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity> {
        check(level.server.isSameThread) { "peripheral cables must be discovered on the server thread" }
        return PeripheralCableTopologyCache.getOrCompute(level, cablePosition) {
            discoverUncached(level, listOf(cablePosition.immutable()))
        }
    }

    private fun discoverUncached(
        level: ServerLevel,
        starts: List<BlockPos>,
    ): PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity> {
        val dimension = level.dimension().toString()
        return PeripheralCableTopology<BlockPos, PeripheralDeviceIdentity>(
            limits = DEFAULT_LIMITS,
            neighbors = { cable ->
                Direction.entries.mapNotNull { direction ->
                    cable.relative(direction).immutable().takeIf { position -> isLoadedCable(level, position) }
                }
            },
            contacts = { cable -> contacts(level, dimension, cable) },
        ).traverse(starts)
    }

    private fun adjacentCables(
        level: ServerLevel,
        position: BlockPos,
    ): List<BlockPos> =
        Direction.entries.mapNotNull { direction ->
            position.relative(direction).immutable().takeIf { adjacent -> isLoadedCable(level, adjacent) }
        }

    private fun contacts(
        level: ServerLevel,
        dimension: String,
        cable: BlockPos,
    ): List<PeripheralDeviceIdentity> =
        Direction.entries.flatMap { direction ->
            val position = cable.relative(direction)
            if (!level.hasChunkAt(position) || PeripheralCableBlocks.contains(level.getBlockState(position))) {
                emptyList()
            } else {
                ComputerAddonHosts.resolvePeripheralContact(level, position, direction.opposite).map { device ->
                    PeripheralDeviceIdentity(device.providerId, dimension, device.anchor.immutable(), device.deviceKey)
                }
            }
        }

    private fun isLoadedCable(
        level: ServerLevel,
        position: BlockPos,
    ): Boolean = level.hasChunkAt(position) && PeripheralCableBlocks.contains(level.getBlockState(position))

    private val DEFAULT_LIMITS =
        PeripheralCableLimits(
            maximumCables = 4096,
            maximumNeighborVisits = 24_576,
            maximumContacts = 1024,
        )
}
