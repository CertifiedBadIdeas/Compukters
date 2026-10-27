/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import ru.lazyhat.compukters.core.display.DisplayFace
import ru.lazyhat.compukters.core.display.DisplayOffset
import ru.lazyhat.compukters.core.display.DisplayPanel
import ru.lazyhat.compukters.core.display.DisplaySurface
import ru.lazyhat.compukters.minecraft.peripheral.DisplayNetworkAccess
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralBlockTransfers
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralCableTopologyCache
import java.util.UUID

/** The saved DisplayDirectory owns both block displays and face attachments. No backing blocks are replaced. */
object AttachedDisplaySurfaces {
    val facings: List<Direction> = listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN)

    internal fun at(
        level: ServerLevel,
        position: BlockPos,
        face: Direction? = null,
    ): DisplaySurface? {
        if (PeripheralBlockTransfers.receiving(level, position)) return null
        val surface = DisplayStorage.get(level).directory.attachedAt(position.x, position.y, position.z) ?: return null
        return surface
            .takeIf {
                (face == null || DisplayWorldAccess.facing(it) == face) && level.hasChunkAt(position) &&
                    !level.getBlockState(position).isAir
            }?.let { DisplayWorldAccess.prepare(level, it) }
    }

    /** Trusted world-building API; interactive callers supply a permission check for every backing block. */
    fun create(
        level: ServerLevel,
        first: BlockPos,
        second: BlockPos,
        face: Direction,
        allowed: (BlockPos) -> Boolean = { true },
    ): UUID {
        check(level.server.isSameThread)
        val geometry = rectangle(first, second, face)
        val directory = DisplayStorage.get(level).directory
        val members =
            geometry.positions.mapIndexed { index, pos ->
                require(level.hasChunkAt(pos) && !level.isOutsideBuildHeight(pos) && level.worldBorder.isWithinBounds(pos)) {
                    "Load the entire display rectangle"
                }
                require(allowed(pos)) { "Cannot modify this backing block" }
                require(!level.getBlockState(pos).isAir) { "Every display tile needs a backing block" }
                require(level.getBlockEntity(pos) !is DisplayBlockEntity) { "Use ordinary display assembly for display blocks" }
                require(directory.attachedAt(pos.x, pos.y, pos.z) == null) { "Backing block already carries a display" }
                DisplayPanel(index % geometry.columns, index / geometry.columns, UUID.randomUUID())
            }
        val surface =
            directory.create(
                geometry.origin.x,
                geometry.origin.y,
                geometry.origin.z,
                facings.indexOf(face),
                geometry.columns,
                geometry.rows,
                members,
                attached = true,
            )
        DisplayWorldAccess.prepare(level, surface)
        DisplayNetworkAccess.consolidate(level, emptySet(), surface)
        PeripheralCableTopologyCache.invalidate(level)
        return surface.id
    }

    data class Rectangle(
        val origin: BlockPos,
        val columns: Int,
        val rows: Int,
        val positions: List<BlockPos>,
    )

    fun rectangle(
        first: BlockPos,
        second: BlockPos,
        face: Direction,
    ): Rectangle {
        val facing = facings.indexOf(face)
        val delta = DisplayOffset(second.x - first.x, second.y - first.y, second.z - first.z)
        require(delta.dot(DisplayFace.normal(facing)) == 0) { "Corners must lie on the same flat face" }
        val horizontal = delta.dot(DisplayFace.right(facing))
        val vertical = delta.dot(DisplayFace.down(facing))
        val columns = kotlin.math.abs(horizontal.toLong()) + 1
        val rows = kotlin.math.abs(vertical.toLong()) + 1
        require(columns in 1..8 && rows in 1..8) { "Display maximum size is 8 by 8 blocks" }
        val offset = DisplayFace.offset(facing, minOf(0, horizontal), minOf(0, vertical))
        val origin = first.offset(offset.x, offset.y, offset.z)
        val positions =
            (0 until rows.toInt()).flatMap { row ->
                (0 until columns.toInt()).map { column ->
                    val at = DisplayFace.offset(facing, column, row)
                    origin.offset(at.x, at.y, at.z)
                }
            }
        return Rectangle(origin, columns.toInt(), rows.toInt(), positions)
    }

    /** Called after a successful block replacement, before another block can occupy the same address. */
    @JvmStatic
    fun backingChanged(
        level: Level,
        pos: BlockPos,
    ) {
        if (level !is ServerLevel || !level.server.isSameThread || PeripheralBlockTransfers.removing(level, pos)) return
        if (DisplayStorage.get(level).directory.attachedAt(pos.x, pos.y, pos.z) != null) DisplayWorldAccess.remove(level, pos)
    }

    internal fun pointer(
        level: ServerLevel,
        hit: BlockHitResult,
    ): ru.lazyhat.compukters.core.display.DisplayPointerTarget? {
        val surface = at(level, hit.blockPos, hit.direction) ?: return null
        val panel = surface.panels.firstOrNull { DisplayWorldAccess.position(surface, it.column, it.row) == hit.blockPos } ?: return null
        val local = hit.location.subtract(hit.blockPos.x.toDouble(), hit.blockPos.y.toDouble(), hit.blockPos.z.toDouble())
        val point =
            ru.lazyhat.compukters.core.display.displayClickAt(
                surface.facing,
                panel.column,
                panel.row,
                surface.canvas.density,
                local.x,
                local.y,
                local.z,
            )
                ?: return null
        return ru.lazyhat.compukters.core.display
            .DisplayPointerTarget(surface.canvas, point.x, point.y)
    }

    internal fun remove(
        level: ServerLevel,
        id: UUID,
    ) {
        val surface =
            DisplayStorage
                .get(level)
                .directory
                .byId(id)
                ?.takeIf { it.attached } ?: return
        surface.panels.forEach { DisplayWorldAccess.remove(level, DisplayWorldAccess.position(surface, it.column, it.row)) }
    }
}

/** Input adapter derives geometry on the server for attached and block-backed displays alike. */
fun displayPointerTarget(
    level: ServerLevel,
    hit: BlockHitResult,
): ru.lazyhat.compukters.core.display.DisplayPointerTarget? =
    (level.getBlockEntity(hit.blockPos) as? DisplayBlockEntity)?.let { DisplayWorldAccess.pointerTarget(level, it, hit) }
        ?: AttachedDisplaySurfaces.pointer(level, hit)

/** Front-face interaction with an attachment, excluding ordinary display blocks. */
fun attachedDisplayPointerTarget(
    level: ServerLevel,
    hit: BlockHitResult,
): ru.lazyhat.compukters.core.display.DisplayPointerTarget? = AttachedDisplaySurfaces.pointer(level, hit)
