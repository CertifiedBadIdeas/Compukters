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

internal data class PeripheralCableContact(
    val position: BlockPos,
    val face: Direction,
)

object PeripheralCableTopologyCache {
    private val levels = WeakHashMap<ServerLevel, MutableMap<BlockPos, PeripheralCableTraversal<BlockPos, PeripheralCableContact>>>()

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
        discover: () -> PeripheralCableTraversal<BlockPos, PeripheralCableContact>,
    ): PeripheralCableTraversal<BlockPos, PeripheralCableContact> {
        val cache = levels.getOrPut(level, ::linkedMapOf)
        val key = computerPosition.immutable()
        cache[key]?.let { return it }
        return discover().also { traversal ->
            val contactCount = (traversal as? PeripheralCableTraversal.Complete)?.contacts?.size ?: 0
            val cachedContacts = cache.values.sumOf { (it as? PeripheralCableTraversal.Complete)?.contacts?.size ?: 0 }
            if (cache.size >= MAXIMUM_COMPUTER_ENTRIES || cachedContacts + contactCount > MAXIMUM_CACHED_CONTACTS) cache.clear()
            cache[key] = traversal
        }
    }

    private const val MAXIMUM_COMPUTER_ENTRIES = 1024
    private const val MAXIMUM_CACHED_CONTACTS = 65_536
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
    ): String {
        val screen =
            ru.lazyhat.compukters.minecraft.display.DisplayNames
                .surface(level, identity)
        if (screen != null) {
            val normalized = normalizePeripheralName(name)
            ru.lazyhat.compukters.minecraft.display.DisplayNames
                .rename(level, screen.id, normalized)
            return normalized
        }
        val member = PeripheralNetworkAccess.member(level, identity)
        if (member == null) return PeripheralDeviceNameStorage.get(level).setName(identity, name)
        val normalized = normalizePeripheralName(name)
        val storage = PeripheralNetworkStorage.get(level)
        storage.directory.setName(member.instance, identity, normalized)
        storage.setDirty()
        return normalized
    }

    fun clearName(
        level: ServerLevel,
        identity: PeripheralDeviceIdentity,
    ): String? {
        val screen =
            ru.lazyhat.compukters.minecraft.display.DisplayNames
                .surface(level, identity)
        if (screen != null) {
            val old = screen.name
            ru.lazyhat.compukters.minecraft.display.DisplayNames
                .rename(level, screen.id, null)
            return old
        }
        val member = PeripheralNetworkAccess.member(level, identity)
        if (member == null) return PeripheralDeviceNameStorage.get(level).clearName(identity)
        val storage = PeripheralNetworkStorage.get(level)
        storage.directory.setName(member.instance, identity, null)
        storage.setDirty()
        return member.name
    }
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
        if (providerId == "compukters-display" && traversal is PeripheralCableTraversal.Complete) {
            return runCatching {
                ru.lazyhat.compukters.minecraft.display.DisplayNames
                    .lookup(level, requestedName, traversal.contacts)
            }.getOrElse { ComputerPeripheralLookupResult(ComputerPeripheralLookupStatus.INVALID_NAME) }
        }
        return lookupComputerPeripheral(
            providerId,
            requestedName,
            traversal,
            PeripheralNetworkAccess.names(level, (traversal as? PeripheralCableTraversal.Complete)?.contacts.orEmpty()),
        )
    }

    @JvmStatic
    fun isReachable(
        level: ServerLevel,
        computerPosition: BlockPos,
        identity: ComputerPeripheralIdentity,
    ): Boolean {
        check(level.server.isSameThread)
        val expected =
            PeripheralDeviceIdentity(identity.providerId, level.dimension().toString(), identity.anchor.immutable(), identity.deviceKey)
        val direct =
            Direction.entries.any { direction ->
                val adjacent = computerPosition.relative(direction)
                level.hasChunkAt(adjacent) && expected in PeripheralDeviceNames.resolveContact(level, adjacent, direction.opposite)
            }
        if (direct) return true
        return PeripheralNetworkAccess.network(level, computerPosition)?.members.orEmpty().any { member ->
            PeripheralNetworkAccess.reachableIdentity(level, computerPosition, member, expected.anchor) == expected
        }
    }
}

object ComputerPeripheralNames {
    @JvmStatic
    fun setName(
        level: ServerLevel,
        identity: ComputerPeripheralIdentity,
        requestedName: String,
    ): String =
        PeripheralDeviceNames.setName(
            level,
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

internal fun isPeripheralReachable(
    identity: PeripheralDeviceIdentity,
    traversal: PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity>,
): Boolean = traversal is PeripheralCableTraversal.Complete && identity in traversal.contacts

internal object PeripheralWorldDiscovery {
    fun discover(
        level: ServerLevel,
        computerPosition: BlockPos,
    ): PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity> {
        check(level.server.isSameThread) { "peripheral discovery must run on the server thread" }
        val direct = resolveContacts(level, PeripheralCableTraversal.Complete(emptySet(), contacts(level, computerPosition).toSet()))
        if (direct !is PeripheralCableTraversal.Complete) return direct
        val network = PeripheralNetworkAccess.network(level, computerPosition)
        val remote =
            network
                ?.members
                .orEmpty()
                .mapNotNull { member -> PeripheralNetworkAccess.reachableIdentity(level, computerPosition, member) }
        val combined = (direct.contacts + remote).toSet()
        return if (combined.size > DEFAULT_LIMITS.maximumContacts) {
            PeripheralCableTraversal.LimitExceeded(PeripheralCableLimit.CONTACTS, DEFAULT_LIMITS.maximumContacts)
        } else {
            direct.copy(contacts = combined)
        }
    }

    fun discoverFromDevice(
        level: ServerLevel,
        contactedPosition: BlockPos,
    ): PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity> {
        check(level.server.isSameThread) { "peripheral discovery must run on the server thread" }
        val network = PeripheralNetworkAccess.network(level, contactedPosition)
        return PeripheralCableTraversal.Complete(
            emptySet(),
            network
                ?.members
                .orEmpty()
                .filterNot { it.isComputer }
                .map { it.identity }
                .toSet(),
        )
    }

    fun discoverFromCable(
        level: ServerLevel,
        cablePosition: BlockPos,
    ): PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity> {
        check(level.server.isSameThread)
        return PeripheralCableTraversal.Complete(emptySet(), emptySet())
    }

    // Keep empty and currently unloaded positions: provider availability can change without rewiring.
    private fun contacts(
        level: ServerLevel,
        cable: BlockPos,
    ): List<PeripheralCableContact> =
        Direction.entries.mapNotNull { direction ->
            val position = cable.relative(direction).immutable()
            if (level.hasChunkAt(position) && PeripheralCableBlocks.contains(level.getBlockState(position))) {
                null
            } else {
                PeripheralCableContact(position, direction.opposite)
            }
        }

    private fun resolveContacts(
        level: ServerLevel,
        traversal: PeripheralCableTraversal<BlockPos, PeripheralCableContact>,
    ): PeripheralCableTraversal<BlockPos, PeripheralDeviceIdentity> =
        resolvePeripheralCableContacts(traversal, DEFAULT_LIMITS.maximumContacts) { contact ->
            if (level.hasChunkAt(contact.position)) {
                PeripheralDeviceNames.resolveContact(level, contact.position, contact.face)
            } else {
                emptyList()
            }
        }

    private val DEFAULT_LIMITS =
        PeripheralCableLimits(
            maximumCables = 4096,
            maximumNeighborVisits = 24_576,
            maximumContacts = 1024,
        )
}
