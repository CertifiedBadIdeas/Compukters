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
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import java.util.UUID

/** Loader adapter for saved exact-instance stamps and configured access range. */
object PeripheralNetworkAccess {
    private var stamps: ((BlockEntity, Boolean) -> UUID?)? = null
    private var range: () -> Int = { 64 }

    @JvmStatic
    fun install(
        stamp: (BlockEntity, Boolean) -> UUID?,
        radius: () -> Int,
    ) {
        check(stamps == null) { "Peripheral identity adapter is already installed" }
        stamps = stamp
        range = radius
    }

    internal fun instance(
        entity: BlockEntity,
        create: Boolean = false,
    ): UUID? = stamps?.invoke(entity, create)

    internal fun radius(): Int = range().coerceIn(1, 1024)

    internal fun network(
        level: ServerLevel,
        computer: BlockPos,
    ): PeripheralNetwork? {
        if (!level.hasChunkAt(computer)) return null
        val entity = level.getBlockEntity(computer) ?: return null
        if (entity is ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity) {
            val screen =
                ru.lazyhat.compukters.minecraft.display.DisplayWorldAccess
                    .surface(level, entity, false) ?: return null
            if (!DisplayNetworkAccess.migrate(level, screen)) return null
            return PeripheralNetworkStorage.get(level).directory.networkOf(screen.id)
        }
        refresh(entity)
        val id = instance(entity) ?: return null
        return PeripheralNetworkStorage.get(level).directory.networkOf(id)
    }

    /** Refresh saved address hints when a stamped member loads at another location. */
    @JvmStatic
    fun refresh(entity: BlockEntity) {
        val level = entity.level as? ServerLevel ?: return
        if (PeripheralBlockTransfers.receiving(level, entity.blockPos)) return
        val stamp = instance(entity) ?: return
        val storage = PeripheralNetworkStorage.get(level)
        val members =
            storage.directory
                .networkOf(stamp)
                ?.members
                .orEmpty()
                .filter { it.instance == stamp }
        val previous = members.firstOrNull() ?: return
        if (previous.identity.dimension == level.dimension().toString() && previous.identity.anchor != entity.blockPos &&
            level.hasChunkAt(previous.identity.anchor)
        ) {
            val old = level.getBlockEntity(previous.identity.anchor)
            if (old != null && instance(old) == stamp) return // A copied instance cannot steal a live member's address.
        }
        if (storage.directory.relocate(stamp, level.dimension().toString(), entity.blockPos)) storage.setDirty()
    }

    @JvmStatic
    fun detach(entity: BlockEntity) {
        val level = entity.level as? ServerLevel ?: return
        if (PeripheralBlockTransfers.removing(level, entity.blockPos)) return
        val stamp = instance(entity) ?: return
        val storage = PeripheralNetworkStorage.get(level)
        if (storage.directory.remove(stamp)) storage.setDirty()
    }

    internal fun member(
        level: ServerLevel,
        identity: PeripheralDeviceIdentity,
    ): PeripheralNetworkMember? {
        if (!level.hasChunkAt(identity.anchor)) return null
        val entity = level.getBlockEntity(identity.anchor) ?: return null
        if (entity is ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity) {
            val screen =
                ru.lazyhat.compukters.minecraft.display.DisplayWorldAccess
                    .surface(level, entity, false) ?: return null
            if (!DisplayNetworkAccess.migrate(level, screen)) return null
            return PeripheralNetworkStorage
                .get(level)
                .directory
                .networkOf(screen.id)
                ?.members
                ?.firstOrNull { it.instance == screen.id }
        }
        val stamp = instance(entity) ?: return null
        return PeripheralNetworkStorage.get(level).directory.networkOf(stamp)?.members?.firstOrNull {
            it.instance == stamp && it.identity == identity
        }
    }

    internal fun names(
        level: ServerLevel,
        identities: Set<PeripheralDeviceIdentity>,
    ): PeripheralDeviceDirectory {
        val legacy = PeripheralDeviceNameStorage.get(level).directory
        return PeripheralDeviceDirectory(
            identities.mapNotNull { identity ->
                val member = member(level, identity)
                val screen =
                    ru.lazyhat.compukters.minecraft.display.DisplayNames
                        .surface(level, identity)
                val name =
                    if (screen != null) {
                        screen.name
                    } else if (member != null) {
                        member.name
                    } else {
                        legacy.nameOf(identity)
                    }
                name?.let { PeripheralDeviceName(identity, it) }
            },
        )
    }

    internal fun reachableIdentity(
        level: ServerLevel,
        computer: BlockPos,
        member: PeripheralNetworkMember,
        contact: BlockPos? = null,
    ): PeripheralDeviceIdentity? {
        if (member.identity.dimension != level.dimension().toString()) return null
        if (member.identity.providerId == "compukters-display") {
            ru.lazyhat.compukters.minecraft.display.DisplayStorage.get(level).directory.byId(member.instance)?.let {
                return DisplayNetworkAccess.reachable(level, computer, it, contact)
            }
        }
        return member.identity.takeIf { (contact == null || contact == it.anchor) && available(level, computer, member) }
    }

    internal fun available(
        level: ServerLevel,
        computer: BlockPos,
        member: PeripheralNetworkMember,
    ): Boolean = availability(level, computer, member) == PeripheralNetworkAvailability.AVAILABLE

    internal fun availability(
        level: ServerLevel,
        computer: BlockPos,
        member: PeripheralNetworkMember,
    ): PeripheralNetworkAvailability {
        if (member.identity.dimension != level.dimension().toString()) return PeripheralNetworkAvailability.OTHER_DIMENSION
        if (member.identity.providerId == "compukters-display") {
            ru.lazyhat.compukters.minecraft.display.DisplayStorage.get(level).directory.byId(member.instance)?.let {
                return DisplayNetworkAccess.availability(level, computer, it)
            }
        }
        if (!PeripheralWorldPositions.inRange(level, computer, member.identity)) {
            return PeripheralNetworkAvailability.OUT_OF_RANGE
        }
        val position = member.identity.anchor
        if (!level.hasChunkAt(position)) return PeripheralNetworkAvailability.UNLOADED
        val entity = level.getBlockEntity(position) ?: return PeripheralNetworkAvailability.REPLACED
        if (instance(entity) != member.instance) return PeripheralNetworkAvailability.REPLACED
        if (member.isComputer) return PeripheralNetworkAvailability.COMPUTER
        return if (net.minecraft.core.Direction.entries.any { face ->
                member.identity in PeripheralDeviceNames.resolveContact(level, position, face)
            }
        ) {
            PeripheralNetworkAvailability.AVAILABLE
        } else {
            PeripheralNetworkAvailability.UNAVAILABLE
        }
    }
}

/** Inspection includes unavailable members; Guest discovery only includes reachable devices. */
enum class PeripheralNetworkAvailability {
    AVAILABLE,
    COMPUTER,
    OUT_OF_RANGE,
    OTHER_DIMENSION,
    UNLOADED,
    REPLACED,
    UNAVAILABLE,
}
