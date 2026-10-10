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
)

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
        scan@ for (cx in ((center.x - radius) shr 4)..((center.x + radius) shr 4)) {
            for (cz in ((center.z - radius) shr 4)..((center.z + radius) shr 4)) {
                val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                for (entity in chunk.blockEntities.values) {
                    if (++inspected > 4096 || devices.size == ConfiguratorOverlaySnapshot.MAX_DEVICES) {
                        truncated = true
                        break@scan
                    }
                    if (entity.isRemoved || !entity.blockPos.closerThan(center, radius.toDouble())) continue
                    val identities =
                        if (entity is ComputerBlockEntity) {
                            emptyList()
                        } else {
                            Direction.entries.flatMap { PeripheralDeviceNames.resolveContact(level, entity.blockPos, it) }.distinct()
                        }
                    if (entity !is ComputerBlockEntity && entity !is DisplayBlockEntity && identities.isEmpty()) continue
                    val instance = PeripheralNetworkAccess.instance(entity)
                    val network = instance?.let(networks::networkOf)
                    val screen = (entity as? DisplayBlockEntity)?.let { directory.byPanel(UUID.fromString(it.checkpointIdentity)) }
                    val names =
                        identities.mapNotNull { identity ->
                            network?.members?.firstOrNull { it.instance == instance && it.identity == identity }?.name
                                ?: PeripheralDeviceNameStorage.get(level).directory.nameOf(identity)
                        }
                    val title = screen?.name ?: names.firstOrNull() ?: entity.blockState.block.descriptionId
                    devices +=
                        ConfiguratorOverlayDevice(entity.blockPos.immutable(), title, network?.id, network?.name.orEmpty(), screen?.id)
                }
            }
        }
        val screens =
            directory
                .snapshot()
                .filter { surface ->
                    surface.panels.any { panel ->
                        DisplayWorldAccess.position(surface, panel.column, panel.row).closerThan(center, radius.toDouble())
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
