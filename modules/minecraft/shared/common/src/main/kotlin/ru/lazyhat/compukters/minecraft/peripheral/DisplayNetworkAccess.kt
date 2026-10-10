/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.core.display.DisplaySurface
import ru.lazyhat.compukters.minecraft.display.DisplayBlock
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import ru.lazyhat.compukters.minecraft.display.DisplayStorage
import ru.lazyhat.compukters.minecraft.display.DisplayWorldAccess
import java.util.UUID
import java.util.WeakHashMap

/** The screen owns membership; physical panel stamps are only used when adopting old saves. */
internal object DisplayNetworkAccess {
    private val migrated = WeakHashMap<ServerLevel, MutableSet<UUID>>()
    private val legacyPending = WeakHashMap<ServerLevel, MutableSet<UUID>>()

    fun member(
        level: ServerLevel,
        screen: DisplaySurface,
    ): PeripheralNetworkMember {
        val panel = screen.panels.first()
        return PeripheralNetworkMember(
            screen.id,
            identity(level, DisplayWorldAccess.position(screen, panel.column, panel.row)),
            screen.name,
        )
    }

    private fun identity(
        level: ServerLevel,
        position: BlockPos,
    ) = PeripheralDeviceIdentity("compukters-display", level.dimension().toString(), position, "text")

    /** A copied or stale stamp must not transfer another block's saved membership. */
    fun physicalSource(
        level: ServerLevel,
        entity: DisplayBlockEntity,
    ): UUID? {
        val stamp = PeripheralNetworkAccess.instance(entity) ?: return null
        val network = PeripheralNetworkStorage.get(level).directory.networkOf(stamp) ?: return stamp
        return stamp.takeIf {
            network.members.any { member ->
                member.instance == stamp && member.identity.providerId == "compukters-display" &&
                    member.identity.dimension == level.dimension().toString() && member.identity.anchor == entity.blockPos
            }
        }
    }

    fun sources(
        level: ServerLevel,
        screen: DisplaySurface,
    ): Set<UUID> =
        setOf(screen.id) +
            screen.panels.mapNotNull { panel ->
                val pos = DisplayWorldAccess.position(screen, panel.column, panel.row)
                val entity = if (level.hasChunkAt(pos)) level.getBlockEntity(pos) as? DisplayBlockEntity else null
                entity?.takeIf { matches(screen, panel.instance, it) }?.let { physicalSource(level, it) }
            }

    fun consolidate(
        level: ServerLevel,
        sources: Set<UUID>,
        screen: DisplaySurface,
    ) {
        val storage = PeripheralNetworkStorage.get(level)
        storage.directory.consolidate(sources, member(level, screen))
        storage.setDirty()
        completed(level).apply {
            removeIf { DisplayStorage.get(level).directory.byId(it) == null }
            add(screen.id)
        }
        legacyPending[level]?.remove(screen.id)
    }

    private fun completed(level: ServerLevel): MutableSet<UUID> = migrated.getOrPut(level) { hashSetOf() }

    /** Wait for exact saved panels before adopting legacy memberships; never inherit by position alone. */
    fun migrate(
        level: ServerLevel,
        screen: DisplaySurface,
    ): Boolean {
        val done = completed(level)
        if (screen.id in done) return true
        val directory = PeripheralNetworkStorage.get(level).directory
        if (directory.networkOf(screen.id) != null) {
            done.add(screen.id)
            return true
        }
        val positions = screen.panels.map { DisplayWorldAccess.position(screen, it.column, it.row) }.toSet()
        val pending = legacyPending.getOrPut(level) { hashSetOf() }
        if (screen.id in pending && positions.any { !level.hasChunkAt(it) }) return false
        val hasLegacy =
            screen.id in pending ||
                directory.snapshot().any { network ->
                    network.members.any {
                        it.identity.providerId == "compukters-display" && it.identity.dimension == level.dimension().toString() &&
                            it.identity.anchor in positions
                    }
                }
        if (!hasLegacy) {
            done.add(screen.id)
            return true
        }
        if (positions.any { !level.hasChunkAt(it) }) {
            pending.add(screen.id)
            return false
        }
        consolidate(level, sources(level, screen), screen)
        return true
    }

    private fun matches(
        screen: DisplaySurface,
        instance: UUID,
        entity: DisplayBlockEntity,
    ): Boolean =
        !entity.isRemoved && entity.checkpointIdentity == instance.toString() &&
            entity.blockState.getValue(DisplayBlock.FACING) == DisplayWorldAccess.facing(screen)

    fun reachable(
        level: ServerLevel,
        computer: BlockPos,
        screen: DisplaySurface,
        contact: BlockPos? = null,
    ): PeripheralDeviceIdentity? =
        screen.panels.firstNotNullOfOrNull { panel ->
            val pos = DisplayWorldAccess.position(screen, panel.column, panel.row)
            if (contact != null && contact != pos) return@firstNotNullOfOrNull null
            val identity = identity(level, pos)
            if (!peripheralInRange(level.dimension().toString(), computer, identity, PeripheralNetworkAccess.radius()) ||
                !level.hasChunkAt(pos)
            ) {
                return@firstNotNullOfOrNull null
            }
            val entity = level.getBlockEntity(pos) as? DisplayBlockEntity ?: return@firstNotNullOfOrNull null
            identity.takeIf { matches(screen, panel.instance, entity) }
        }

    fun availability(
        level: ServerLevel,
        computer: BlockPos,
        screen: DisplaySurface,
    ): PeripheralNetworkAvailability {
        if (reachable(level, computer, screen) != null) return PeripheralNetworkAvailability.AVAILABLE
        val inRange =
            screen.panels
                .map { identity(level, DisplayWorldAccess.position(screen, it.column, it.row)) }
                .filter { peripheralInRange(level.dimension().toString(), computer, it, PeripheralNetworkAccess.radius()) }
        if (inRange.isEmpty()) return PeripheralNetworkAvailability.OUT_OF_RANGE
        return if (inRange.any { !level.hasChunkAt(it.anchor) }) {
            PeripheralNetworkAvailability.UNLOADED
        } else {
            PeripheralNetworkAvailability.REPLACED
        }
    }

    fun refresh(
        level: ServerLevel,
        screen: DisplaySurface,
    ) {
        val storage = PeripheralNetworkStorage.get(level)
        val network = storage.directory.networkOf(screen.id) ?: return
        val updated = member(level, screen)
        storage.directory.bind(network.id, updated)
        storage.directory.setName(screen.id, updated.identity, screen.name)
        storage.setDirty()
    }

    fun checkSplit(
        level: ServerLevel,
        screen: DisplaySurface,
    ) {
        check(migrate(level, screen)) { "Load screen panels to adopt their old network bindings before splitting" }
        PeripheralNetworkStorage.get(level).directory.checkReplacementCapacity(screen.id, screen.panels.size)
    }

    fun split(
        level: ServerLevel,
        previous: UUID,
        screens: List<DisplaySurface>,
    ) {
        val storage = PeripheralNetworkStorage.get(level)
        storage.directory.replace(previous, screens.map { member(level, it) })
        storage.setDirty()
        migrated[level]?.remove(previous)
        legacyPending[level]?.remove(previous)
        completed(level).addAll(screens.map { it.id })
    }

    fun remove(
        level: ServerLevel,
        id: UUID,
    ) {
        val storage = PeripheralNetworkStorage.get(level)
        if (storage.directory.remove(id)) storage.setDirty()
        migrated[level]?.remove(id)
        legacyPending[level]?.remove(id)
    }
}
