/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import ru.lazyhat.compukters.minecraft.display.DisplayStorage
import ru.lazyhat.compukters.minecraft.display.DisplayWorldAccess
import java.util.UUID

/** Read-only observations; no identity or membership is created by holding the tool. */
data class ConfiguratorOverlayDevice(
    val position: BlockPos,
    val title: String,
    val network: UUID? = null,
    val networkName: String = "",
    val screen: UUID? = null,
    val bounds: ConfiguratorDeviceBounds? = null,
) {
    init {
        require(bounds == null || bounds.contains(position))
    }

    fun contains(block: BlockPos?): Boolean = block != null && (bounds?.contains(block) ?: (position == block))

    fun box(): AABB = bounds?.box() ?: AABB(position)
}

/** Bounded block geometry; identities and exact-instance stamps remain anchored to one block entity. */
data class ConfiguratorDeviceBounds(
    val minimum: BlockPos,
    val maximum: BlockPos,
) {
    init {
        require(maximum.x.toLong() - minimum.x in 0L until 64L)
        require(maximum.y.toLong() - minimum.y in 0L until 64L)
        require(maximum.z.toLong() - minimum.z in 0L until 64L)
    }

    fun contains(block: BlockPos): Boolean =
        block.x in minimum.x..maximum.x && block.y in minimum.y..maximum.y && block.z in minimum.z..maximum.z

    fun box(): AABB = AABB(minimum).minmax(AABB(maximum))

    companion object {
        fun from(
            box: AABB?,
            anchor: BlockPos,
            contact: BlockPos,
        ): ConfiguratorDeviceBounds? {
            if (box == null) return null
            val coordinates = listOf(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
            if (coordinates.any { !it.isFinite() || it != kotlin.math.floor(it) || it !in -30_000_000.0..30_000_000.0 }) return null
            return runCatching {
                ConfiguratorDeviceBounds(
                    BlockPos.containing(box.minX, box.minY, box.minZ),
                    BlockPos.containing(box.maxX - 1, box.maxY - 1, box.maxZ - 1),
                ).takeIf { it.contains(anchor) && it.contains(contact) }
            }.getOrNull()
        }
    }
}

data class ConfiguratorOverlayScreen(
    val id: UUID,
    val name: String,
    val origin: BlockPos,
    val facing: Direction,
    val columns: Int,
    val rows: Int,
    val panels: List<BlockPos>,
) {
    init {
        require(columns in 1..8 && rows in 1..8 && facing.axis.isHorizontal)
        require(panels.size in 1..columns * rows && panels.distinct().size == panels.size)
        require(panels.all { it in cells() })
    }

    fun position(
        column: Int,
        row: Int,
    ): BlockPos = origin.relative(facing.counterClockWise, column).below(row)

    fun cells(): List<BlockPos> = (0 until rows).flatMap { row -> (0 until columns).map { position(it, row) } }
}

data class ConfiguratorOverlaySnapshot(
    val devices: List<ConfiguratorOverlayDevice>,
    val screens: List<ConfiguratorOverlayScreen>,
    val truncated: Boolean = false,
) {
    init {
        require(devices.size <= MAX_DEVICES && screens.size <= MAX_SCREENS)
        require(devices.map { it.position }.distinct().size == devices.size)
        require(screens.map { it.id }.distinct().size == screens.size)
        require(screens.sumOf { it.columns * it.rows } <= ru.lazyhat.compukters.core.display.DisplayDirectory.MAXIMUM_WORLD_BLOCK_AREA)
    }

    companion object {
        const val RADIUS = 16
        const val MAX_DEVICES = 512
        const val MAX_SCREENS = 128
    }
}

object ConfiguratorOverlayServer {
    fun collect(
        player: ServerPlayer,
        hand: InteractionHand,
    ): ConfiguratorOverlaySnapshot? {
        if (player.getItemInHand(hand).item !is PeripheralConfiguratorItem) return null
        val level = player.level() as? ServerLevel ?: return null
        return collect(level, player.blockPosition())
    }

    fun collect(
        level: ServerLevel,
        center: BlockPos,
    ): ConfiguratorOverlaySnapshot {
        check(level.server.isSameThread)
        val radius = ConfiguratorOverlaySnapshot.RADIUS
        val networks = PeripheralNetworkStorage.get(level).directory
        val directory = DisplayStorage.get(level).directory
        val devices = mutableListOf<ConfiguratorOverlayDevice>()
        var inspected = 0
        var truncated = false
        val worldCenter =
            PeripheralWorldPositions.project(level, Vec3.atCenterOf(center))
                ?: return ConfiguratorOverlaySnapshot(emptyList(), emptyList())
        val scanCenter = BlockPos.containing(worldCenter)
        val ordinary =
            sequence {
                for (cx in ((scanCenter.x - radius) shr 4)..((scanCenter.x + radius) shr 4)) {
                    for (cz in ((scanCenter.z - radius) shr 4)..((scanCenter.z + radius) shr 4)) {
                        val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                        yieldAll(chunk.blockEntities.values)
                    }
                }
            }
        for (
        entity in (ordinary + PeripheralWorldPositions.nearby(level, Vec3.atCenterOf(center), radius.toDouble()))
            .distinctBy { it.blockPos }
        ) {
            if (++inspected > 4096 || devices.size == ConfiguratorOverlaySnapshot.MAX_DEVICES) {
                truncated = true
                break
            }
            if (entity.isRemoved ||
                !PeripheralWorldPositions.within(
                    level,
                    Vec3.atCenterOf(entity.blockPos),
                    Vec3.atCenterOf(center),
                    radius.toDouble(),
                )
            ) {
                continue
            }
            val geometry =
                if (entity is ComputerBlockEntity) {
                    emptyList()
                } else {
                    Direction.entries
                        .flatMap { ComputerAddonHosts.resolvePeripheralGeometry(level, entity.blockPos, it) }
                        .distinctBy { it.identity }
                }
            if (entity !is ComputerBlockEntity && entity !is DisplayBlockEntity && geometry.isEmpty()) continue
            val position =
                if (entity is ComputerBlockEntity || entity is DisplayBlockEntity) entity.blockPos else geometry.first().identity.anchor
            if (devices.any { it.position == position } || !level.hasChunkAt(position)) continue
            val anchor = level.getBlockEntity(position)?.takeUnless { it.isRemoved } ?: continue
            val identities =
                geometry.map {
                    PeripheralDeviceIdentity(
                        it.identity.providerId,
                        level.dimension().toString(),
                        it.identity.anchor,
                        it.identity.deviceKey,
                    )
                }
            val screen = (anchor as? DisplayBlockEntity)?.let { directory.byPanel(UUID.fromString(it.checkpointIdentity)) }
            val instance = screen?.id ?: PeripheralNetworkAccess.instance(anchor)
            val network = instance?.let(networks::networkOf)
            val names =
                identities.mapNotNull { identity ->
                    network?.members?.firstOrNull { it.instance == instance && it.identity == identity }?.name
                        ?: PeripheralDeviceNameStorage.get(level).directory.nameOf(identity)
                }
            val title = screen?.name ?: names.firstOrNull() ?: anchor.blockState.block.descriptionId
            val bounds = geometry.firstNotNullOfOrNull { ConfiguratorDeviceBounds.from(it.bounds, position, entity.blockPos) }
            devices +=
                ConfiguratorOverlayDevice(position.immutable(), title, network?.id, network?.name.orEmpty(), screen?.id, bounds)
        }
        val screens =
            directory
                .snapshot()
                .filter { surface ->
                    surface.panels.any { panel ->
                        PeripheralWorldPositions.within(
                            level,
                            Vec3.atCenterOf(DisplayWorldAccess.position(surface, panel.column, panel.row)),
                            Vec3.atCenterOf(center),
                            radius.toDouble(),
                        )
                    }
                }.map { surface ->
                    ConfiguratorOverlayScreen(
                        surface.id,
                        surface.name.orEmpty(),
                        BlockPos(surface.originX, surface.originY, surface.originZ),
                        DisplayWorldAccess.facing(surface),
                        surface.canvas.columns,
                        surface.canvas.rows,
                        surface.panels.map { DisplayWorldAccess.position(surface, it.column, it.row) },
                    )
                }
        return ConfiguratorOverlaySnapshot(devices, screens, truncated)
    }
}
