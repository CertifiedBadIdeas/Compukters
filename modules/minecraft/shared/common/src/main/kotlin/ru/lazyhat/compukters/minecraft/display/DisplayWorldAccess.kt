/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukters.core.display.DisplayPanel
import ru.lazyhat.compukters.core.display.DisplaySurface
import ru.lazyhat.compukters.minecraft.peripheral.DisplayNetworkAccess
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralCableTopologyCache
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralNetworkAccess
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralNetworkStorage
import java.util.UUID

/** All canvas mutations and membership changes are confined to the owning server thread. */
internal object DisplayWorldAccess {
    private data class TickBudget(
        var tick: Long = -1,
        var draw: Long = 0,
        var sync: Int = 0,
    )

    internal data class Publication(
        val id: UUID,
        val revision: Long,
        val mode: Int,
        val panels: String,
        val tiles: Map<UUID, ByteArray>,
        val remaining: MutableSet<UUID>,
        val sourceRevision: Long,
    )

    private val publications = java.util.WeakHashMap<ServerLevel, MutableMap<UUID, Publication>>()
    private var publicationSequence = 0L

    fun publication(
        level: ServerLevel,
        surface: DisplaySurface,
    ): Publication {
        val cache = publications.getOrPut(level) { linkedMapOf() }
        cache.keys.removeIf { DisplayStorage.get(level).directory.byId(it) == null }
        val members = surface.panels.associate { it.instance to position(surface, it.column, it.row) }
        val positions =
            members.values
                .map { it.asLong() }
                .sorted()
                .joinToString(",")
        val previous = cache[surface.id]
        previous?.remaining?.removeIf { instance ->
            val pos = members[instance]
            pos == null || !level.hasChunkAt(pos) ||
                (level.getBlockEntity(pos) as? DisplayBlockEntity)?.checkpointIdentity != instance.toString()
        }
        if (previous != null && previous.panels == positions && previous.tiles.keys == members.keys &&
            (previous.remaining.isNotEmpty() || previous.sourceRevision == surface.canvas.revision)
        ) {
            return previous
        }
        return Publication(
            surface.id,
            ++publicationSequence,
            surface.canvas.mode,
            positions,
            surface.panels.associate { it.instance to surface.canvas.tile(it.column, it.row) },
            members.filterValues { level.hasChunkAt(it) }.keys.toMutableSet(),
            surface.canvas.revision,
        ).also { cache[surface.id] = it }
    }

    private val budgets = java.util.WeakHashMap<ServerLevel, TickBudget>()

    private fun budget(level: ServerLevel): TickBudget {
        val budget = budgets.getOrPut(level) { TickBudget() }
        if (budget.tick != level.gameTime) {
            budget.tick = level.gameTime
            budget.draw = 0
            budget.sync = 0
        }
        return budget
    }

    fun admitSync(
        level: ServerLevel,
        bytes: Int,
    ): Boolean {
        val budget = budget(level)
        if (budget.sync + bytes > 512 * 1024) return false
        budget.sync += bytes
        return true
    }

    private fun attachBudget(
        level: ServerLevel,
        surface: DisplaySurface,
    ): DisplaySurface {
        surface.canvas.admitWork = { work ->
            val budget = budget(level)
            check(budget.draw + work <= 4L * 1024 * 1024) { "Display pixel work budget exhausted for this tick" }
            budget.draw += work
        }
        return surface
    }

    private val facings = listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)

    fun surface(
        level: ServerLevel,
        entity: DisplayBlockEntity,
        create: Boolean = true,
    ): DisplaySurface? {
        check(level.server.isSameThread)
        val directory = DisplayStorage.get(level).directory
        val id = UUID.fromString(entity.checkpointIdentity)
        directory.byPanel(id)?.let { surface ->
            val panel = surface.panels.single { it.instance == id }
            return surface
                .takeIf {
                    position(it, panel.column, panel.row) == entity.blockPos &&
                        facing(it) == entity.blockState.getValue(DisplayBlock.FACING)
                }?.let {
                    DisplayNetworkAccess.migrate(level, it)
                    attachBudget(level, it)
                }
        }
        if (!create) return null
        // Placing a block beyond world limits leaves an unavailable black panel, never crashes its tick.
        val screens = directory.snapshot()
        if (screens.size >= ru.lazyhat.compukters.core.display.DisplayDirectory.MAXIMUM_SCREENS ||
            screens.sumOf { it.canvas.columns * it.canvas.rows } >=
            ru.lazyhat.compukters.core.display.DisplayDirectory.MAXIMUM_WORLD_BLOCK_AREA
        ) {
            return null
        }
        val position = entity.blockPos
        val created =
            directory.create(
                position.x,
                position.y,
                position.z,
                facings.indexOf(entity.blockState.getValue(DisplayBlock.FACING)),
                1,
                1,
                listOf(DisplayPanel(0, 0, id)),
            )
        val identity =
            ru.lazyhat.compukters.minecraft.peripheral
                .PeripheralDeviceIdentity("compukters-display", level.dimension().toString(), position, "text")
        val legacyName =
            PeripheralNetworkAccess.instance(entity)?.let { stamp ->
                PeripheralNetworkStorage
                    .get(level)
                    .directory
                    .networkOf(stamp)
                    ?.members
                    ?.firstOrNull { it.instance == stamp }
                    ?.name
            }
                ?: ru.lazyhat.compukters.minecraft.peripheral.PeripheralDeviceNameStorage
                    .get(level)
                    .directory
                    .nameOf(identity)
        if (legacyName != null) {
            directory.rename(created.id, legacyName)
            ru.lazyhat.compukters.minecraft.peripheral.PeripheralDeviceNameStorage
                .get(level)
                .clearName(identity)
        }
        DisplayNetworkAccess.migrate(level, created)
        return attachBudget(level, created)
    }

    fun position(
        surface: DisplaySurface,
        column: Int,
        row: Int,
    ): BlockPos = BlockPos(surface.originX, surface.originY, surface.originZ).relative(facing(surface).counterClockWise, column).below(row)

    fun facing(surface: DisplaySurface): Direction = facings[surface.facing]

    fun remove(
        level: ServerLevel,
        removedPosition: BlockPos,
    ) {
        val directory = DisplayStorage.get(level).directory
        val surface =
            directory.snapshot().firstOrNull { candidate ->
                candidate.panels.any { position(candidate, it.column, it.row) == removedPosition }
            } ?: return
        val member = surface.panels.single { position(surface, it.column, it.row) == removedPosition }
        directory.remove(member.instance)
        val remaining = directory.byId(surface.id)
        if (remaining == null) {
            publications[level]?.remove(surface.id)
            DisplayNetworkAccess.remove(level, surface.id)
        } else {
            DisplayNetworkAccess.refresh(level, remaining)
        }
        PeripheralCableTopologyCache.invalidate(level)
    }

    fun assemble(
        level: ServerLevel,
        first: BlockPos,
        second: BlockPos,
    ): DisplaySurface {
        check(level.server.isSameThread)
        require(level.hasChunkAt(first) && level.hasChunkAt(second)) { "Display corners must be loaded" }
        val a = requireNotNull(level.getBlockEntity(first) as? DisplayBlockEntity)
        val b = requireNotNull(level.getBlockEntity(second) as? DisplayBlockEntity)
        val facing = a.blockState.getValue(DisplayBlock.FACING)
        require(b.blockState.getValue(DisplayBlock.FACING) == facing) { "Display orientations differ" }
        val right = facing.counterClockWise
        val delta = second.subtract(first)
        require(delta.x * facing.stepX + delta.z * facing.stepZ == 0) { "Display must occupy one plane" }
        val horizontal = delta.x * right.stepX + delta.z * right.stepZ
        val columns = kotlin.math.abs(horizontal.toLong()) + 1
        val rows = kotlin.math.abs(delta.y.toLong()) + 1
        require(columns in 1..8 && rows in 1..8) { "Display maximum size is 8 by 8 blocks" }
        val origin = first.relative(right, minOf(0, horizontal)).above(maxOf(0, delta.y))
        val directory = DisplayStorage.get(level).directory
        val panels = mutableListOf<DisplayPanel>()
        val oldScreens = linkedSetOf<DisplaySurface>()
        val networkSources = linkedSetOf<UUID>()
        for (row in 0 until rows.toInt()) {
            for (column in 0 until columns.toInt()) {
                val position = origin.relative(right, column).below(row)
                require(level.hasChunkAt(position)) { "Entire display rectangle must be loaded during assembly" }
                val entity = level.getBlockEntity(position) as? DisplayBlockEntity ?: continue
                require(entity.blockState.getValue(DisplayBlock.FACING) == facing) { "Display orientations differ" }
                val old = surface(level, entity, false)
                require(old == null || old.panels.size == 1) { "Panel already belongs to a composite display" }
                if (old != null) {
                    oldScreens += old
                    networkSources += DisplayNetworkAccess.sources(level, old)
                }
                PeripheralNetworkAccess.instance(entity)?.let { networkSources += it }
                panels += DisplayPanel(column, row, UUID.fromString(entity.checkpointIdentity))
            }
        }
        require(panels.isNotEmpty())
        val retainedArea = directory.snapshot().filterNot { it in oldScreens }.sumOf { it.canvas.columns * it.canvas.rows }
        check(retainedArea + columns * rows <= ru.lazyhat.compukters.core.display.DisplayDirectory.MAXIMUM_WORLD_BLOCK_AREA)
        check(directory.snapshot().size - oldScreens.size < ru.lazyhat.compukters.core.display.DisplayDirectory.MAXIMUM_SCREENS)
        oldScreens.forEach { old -> old.panels.forEach { directory.remove(it.instance) } }
        val result = directory.create(origin.x, origin.y, origin.z, facings.indexOf(facing), columns.toInt(), rows.toInt(), panels)
        DisplayNetworkAccess.consolidate(level, networkSources, result)
        panels.forEach { panel -> level.getBlockEntity(position(result, panel.column, panel.row))?.setChanged() }
        PeripheralCableTopologyCache.invalidate(level)
        return result
    }

    fun join(
        level: ServerLevel,
        id: UUID,
        entity: DisplayBlockEntity,
    ) {
        val directory = DisplayStorage.get(level).directory
        val surface = requireNotNull(directory.byId(id)) { "Selected screen was deleted" }
        require(entity.blockState.getValue(DisplayBlock.FACING) == facing(surface))
        val delta = entity.blockPos.subtract(BlockPos(surface.originX, surface.originY, surface.originZ))
        val right = facing(surface).counterClockWise
        require(delta.x * facing(surface).stepX + delta.z * facing(surface).stepZ == 0)
        val column = delta.x * right.stepX + delta.z * right.stepZ
        val row = -delta.y
        require(column in 0 until surface.canvas.columns && row in 0 until surface.canvas.rows) { "Panel lies outside the selected canvas" }
        require(surface.panels.none { it.column == column && it.row == row })
        val old = surface(level, entity, false)
        require(old == null || old.panels.size == 1) { "Panel already belongs to a composite screen" }
        require(DisplayNetworkAccess.migrate(level, surface)) { "Load screen panels to adopt their old network bindings before joining" }
        val networkSources =
            DisplayNetworkAccess.sources(level, surface) +
                old?.let { DisplayNetworkAccess.sources(level, it) }.orEmpty() +
                listOfNotNull(PeripheralNetworkAccess.instance(entity))
        val instance = UUID.fromString(entity.checkpointIdentity)
        if (old != null) directory.remove(instance)
        directory.join(id, DisplayPanel(column, row, instance))
        DisplayNetworkAccess.consolidate(level, networkSources.toSet(), requireNotNull(directory.byId(id)))
        entity.setChanged()
        PeripheralCableTopologyCache.invalidate(level)
    }
}
