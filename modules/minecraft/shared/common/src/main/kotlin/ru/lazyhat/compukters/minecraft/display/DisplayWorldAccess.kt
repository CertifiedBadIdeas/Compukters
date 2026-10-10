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
        val holes =
            directory.snapshot().filter { screen ->
                if (facing(screen) != entity.blockState.getValue(DisplayBlock.FACING)) return@filter false
                val delta = entity.blockPos.subtract(BlockPos(screen.originX, screen.originY, screen.originZ))
                val right = facing(screen).counterClockWise
                val column = delta.x * right.stepX + delta.z * right.stepZ
                val row = -delta.y
                delta.x * facing(screen).stepX + delta.z * facing(screen).stepZ == 0 &&
                    column in 0 until screen.canvas.columns && row in 0 until screen.canvas.rows &&
                    screen.panels.none { it.column == column && it.row == row }
            }
        if (holes.size == 1 && runCatching { join(level, holes.single().id, entity) }.isSuccess) {
            return directory.byPanel(id)?.let { attachBudget(level, it) }
        }
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
            DisplayNetworkAccess.physicalSource(level, entity)?.let { stamp ->
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
                DisplayNetworkAccess.physicalSource(level, entity)?.let { networkSources += it }
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

    fun split(
        level: ServerLevel,
        id: UUID,
    ) {
        check(level.server.isSameThread)
        val directory = DisplayStorage.get(level).directory
        val screen = requireNotNull(directory.byId(id)) { "Selected screen was deleted" }
        screen.panels.forEach { panel ->
            val position = position(screen, panel.column, panel.row)
            require(level.hasChunkAt(position)) { "Load all screen panels before splitting" }
            val entity = level.getBlockEntity(position) as? DisplayBlockEntity
            require(
                entity?.checkpointIdentity == panel.instance.toString() && entity.blockState.getValue(
                    DisplayBlock.FACING,
                ) == facing(screen),
            ) {
                "Screen panel was replaced"
            }
        }
        DisplayNetworkAccess.checkSplit(level, screen)
        val screens = directory.split(id)
        DisplayNetworkAccess.split(level, id, screens)
        publications[level]?.remove(id)
        screens.forEach { screen -> level.getBlockEntity(BlockPos(screen.originX, screen.originY, screen.originZ))?.setChanged() }
        PeripheralCableTopologyCache.invalidate(level)
    }

    fun join(
        level: ServerLevel,
        id: UUID,
        entity: DisplayBlockEntity,
    ) {
        check(level.server.isSameThread)
        val directory = DisplayStorage.get(level).directory
        val screen = requireNotNull(directory.byId(id)) { "Selected screen was deleted" }
        require(entity.blockState.getValue(DisplayBlock.FACING) == facing(screen)) { "Display orientations differ" }
        val delta = entity.blockPos.subtract(BlockPos(screen.originX, screen.originY, screen.originZ))
        val right = facing(screen).counterClockWise
        require(delta.x * facing(screen).stepX + delta.z * facing(screen).stepZ == 0) { "Display must occupy one plane" }
        val column = delta.x * right.stepX + delta.z * right.stepZ
        val row = -delta.y
        val minColumn = minOf(0, column)
        val minRow = minOf(0, row)
        val columns = maxOf(screen.canvas.columns - 1, column) - minColumn + 1
        val rows = maxOf(screen.canvas.rows - 1, row) - minRow + 1
        require(columns in 1..8 && rows in 1..8) { "Display maximum size is 8 by 8 blocks" }
        val expanding = columns != screen.canvas.columns || rows != screen.canvas.rows
        val origin = position(screen, minColumn, minRow)
        val candidates =
            if (expanding) {
                (0 until rows).flatMap { r ->
                    (0 until columns).mapNotNull { c ->
                        val pos = origin.relative(right, c).below(r)
                        require(level.hasChunkAt(pos)) { "Entire expanded rectangle must be loaded" }
                        level.getBlockEntity(pos) as? DisplayBlockEntity
                    }
                }
            } else {
                listOf(entity)
            }
        val additions = linkedMapOf<DisplayBlockEntity, DisplaySurface?>()
        for (candidate in candidates) {
            require(candidate.blockState.getValue(DisplayBlock.FACING) == facing(screen)) { "Display orientations differ" }
            val old = surface(level, candidate, false)
            if (old?.id == id) continue
            require(old == null || old.canvas.columns * old.canvas.rows == 1) { "Panel already belongs to a composite screen" }
            additions[candidate] = old
        }
        if (additions.isEmpty()) return
        require(DisplayNetworkAccess.migrate(level, screen)) { "Load screen panels to adopt their old network bindings before joining" }
        val oldScreens = additions.values.filterNotNull().toSet()
        val retainedArea = directory.snapshot().filterNot { it.id == id || it in oldScreens }.sumOf { it.canvas.columns * it.canvas.rows }
        check(retainedArea + columns * rows <= ru.lazyhat.compukters.core.display.DisplayDirectory.MAXIMUM_WORLD_BLOCK_AREA) {
            "World display area limit reached"
        }
        val sources =
            DisplayNetworkAccess.sources(level, screen) + oldScreens.flatMap { DisplayNetworkAccess.sources(level, it) } +
                additions.keys.mapNotNull { DisplayNetworkAccess.physicalSource(level, it) }
        for (candidate in additions.keys) {
            val pos = candidate.blockPos
            require(screen.panels.none { position(screen, it.column, it.row) == pos }) { "Display slot is occupied" }
        }
        val instances = additions.keys.map { UUID.fromString(it.checkpointIdentity) }
        require(
            instances.distinct().size == instances.size &&
                instances.all { instance ->
                    val owner = directory.byPanel(instance)
                    owner == null || owner in oldScreens
                },
        ) { "A copied panel cannot take an existing screen member's identity" }
        oldScreens.forEach { old -> old.panels.forEach { directory.remove(it.instance) } }
        val result =
            if (expanding) {
                directory.expand(id, origin.x, origin.y, origin.z, columns, rows, -minColumn, -minRow)
            } else {
                screen
            }
        additions.keys.forEach { candidate ->
            val offset = candidate.blockPos.subtract(origin)
            directory.join(
                id,
                DisplayPanel(offset.x * right.stepX + offset.z * right.stepZ, -offset.y, UUID.fromString(candidate.checkpointIdentity)),
            )
            candidate.setChanged()
        }
        DisplayNetworkAccess.consolidate(level, sources.toSet(), result)
        PeripheralCableTopologyCache.invalidate(level)
    }
}
