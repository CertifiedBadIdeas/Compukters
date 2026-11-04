/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import ru.lazyhat.compukters.core.display.DisplaySurface
import ru.lazyhat.compukters.minecraft.peripheral.DisplayNetworkAccess

/** Surface metadata participates in the same explicit move as its ordinary backing blocks. */
internal class AttachedDisplayTransfers private constructor(
    private val source: ServerLevel,
    private val destination: ServerLevel,
    private val positions: Map<BlockPos, BlockPos>,
    private val screens: List<DisplaySurface>,
    private val blocks: Map<BlockPos, Block>,
) {
    val sourcePositions: Set<BlockPos> get() = blocks.keys
    val destinationPositions: List<BlockPos> get() = blocks.keys.map { positions.getValue(it) }

    private val removedBacking = mutableSetOf<BlockPos>()

    /** Explicit permanent bulk deletion proves vacancy even after the native plot stops existing. */
    fun removing(removed: (BlockPos) -> Boolean) {
        removedBacking.addAll(blocks.keys.filter(removed))
    }

    private fun vacated(position: BlockPos): Boolean =
        if (source.hasChunkAt(position)) source.getBlockState(position).isAir else position in removedBacking

    fun finish() {
        val directory = DisplayStorage.get(source).directory
        for (captured in screens) {
            val screen = directory.byId(captured.id) ?: continue
            val successful =
                screen.panels.filter { panel ->
                    val from = DisplayWorldAccess.position(screen, panel.column, panel.row)
                    val to = positions[from] ?: return@filter false
                    vacated(from) && destination.hasChunkAt(to) && destination.getBlockState(to).block === blocks[from]
                }
            if (successful.isEmpty()) {
                for (panel in screen.panels) {
                    val from = DisplayWorldAccess.position(screen, panel.column, panel.row)
                    if (source.hasChunkAt(from) &&
                        source.getBlockState(from).block !== blocks[from]
                    ) {
                        DisplayWorldAccess.remove(source, from)
                    }
                }
                continue
            }
            val pieces =
                if (successful.size == screen.panels.size) {
                    listOf(screen)
                } else {
                    DisplayNetworkAccess.checkSplit(source, screen)
                    val replacements = directory.split(screen.id)
                    DisplayNetworkAccess.split(source, screen.id, replacements)
                    replacements.also { split ->
                        screen.name?.let {
                            directory.rename(split.first().id, it)
                            DisplayNetworkAccess.refresh(source, split.first())
                        }
                    }
                }
            for (piece in pieces) {
                val panel = piece.panels.first()
                val from = DisplayWorldAccess.position(piece, panel.column, panel.row)
                val to = positions[from] ?: continue
                val translation = to.subtract(from)
                if (piece.panels.all { p ->
                        val old = DisplayWorldAccess.position(piece, p.column, p.row)
                        val next = positions[old]
                        next == old.offset(translation) && vacated(old) && destination.hasChunkAt(next!!) &&
                            destination.getBlockState(next).block === blocks[old]
                    }
                ) {
                    DisplayWorldAccess.relocate(
                        source,
                        destination,
                        piece.id,
                        BlockPos(piece.originX, piece.originY, piece.originZ).offset(translation),
                        DisplayWorldAccess.facing(piece),
                    )
                } else {
                    for (p in piece.panels) {
                        val old = DisplayWorldAccess.position(piece, p.column, p.row)
                        if (source.hasChunkAt(old) &&
                            source.getBlockState(old).block !== blocks[old]
                        ) {
                            DisplayWorldAccess.remove(source, old)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Journal authority is supplied by the coordinator, after obsolete copies are removed. */
        fun recover(
            source: ServerLevel,
            destination: ServerLevel,
            positions: Map<BlockPos, BlockPos>,
            committed: Boolean,
        ) {
            val winner = if (committed) destination else source
            for (originWorld in listOf(source, destination).distinct()) {
                val mappings =
                    buildList {
                        if (originWorld === source) add(if (committed) positions else positions.keys.associateWith { it })
                        if (originWorld ===
                            destination
                        ) {
                            add(
                                if (committed) {
                                    positions.values.associateWith { it }
                                } else {
                                    positions.entries.associate {
                                        it.value to
                                            it.key
                                    }
                                },
                            )
                        }
                    }
                for (screen in DisplayStorage
                    .get(originWorld)
                    .directory
                    .snapshot()
                    .filter { it.attached }) {
                    val panels = screen.panels.map { DisplayWorldAccess.position(screen, it.column, it.row) }
                    val mapping = mappings.firstOrNull { map -> panels.all { it in map } } ?: continue
                    val translation = mapping.getValue(panels.first()).subtract(panels.first())
                    require(
                        panels.all { from ->
                            val to = mapping.getValue(from)
                            to == from.offset(translation) && winner.hasChunkAt(to) && !winner.getBlockState(to).isAir
                        },
                    ) { "Recovered attached display has missing backing or inconsistent translation" }
                    DisplayWorldAccess.relocate(
                        originWorld,
                        winner,
                        screen.id,
                        BlockPos(screen.originX, screen.originY, screen.originZ).offset(translation),
                        DisplayWorldAccess.facing(screen),
                    )
                }
            }
        }

        fun begin(
            source: ServerLevel,
            destination: ServerLevel,
            positions: Map<BlockPos, BlockPos>,
        ): AttachedDisplayTransfers {
            val directory = DisplayStorage.get(source).directory
            val affected =
                directory.snapshot().filter { screen ->
                    screen.attached &&
                        screen.panels.any { DisplayWorldAccess.position(screen, it.column, it.row) in positions }
                }
            val targetDirectory = DisplayStorage.get(destination).directory
            for (screen in affected) {
                for (panel in screen.panels) {
                    val from = DisplayWorldAccess.position(screen, panel.column, panel.row)
                    val to = positions[from] ?: continue
                    // The coordinator may materialize a new native plot only after guards open.
                    // finish still requires loaded, matching destination backing blocks.
                    require(targetDirectory.attachedAt(to.x, to.y, to.z) == null) { "Transfer destination already carries a display" }
                }
            }
            for (screen in affected) {
                if (screen.panels.any { DisplayWorldAccess.position(screen, it.column, it.row) !in positions }) {
                    DisplayWorldAccess.split(source, screen.id)
                    screen.name?.let { name ->
                        val first = requireNotNull(directory.byPanel(screen.panels.first().instance))
                        directory.rename(first.id, name)
                        DisplayNetworkAccess.refresh(source, first)
                    }
                }
            }
            val screens =
                directory.snapshot().filter { screen ->
                    screen.attached &&
                        screen.panels.all { DisplayWorldAccess.position(screen, it.column, it.row) in positions }
                }
            val backing =
                screens
                    .flatMap { screen ->
                        screen.panels.map { DisplayWorldAccess.position(screen, it.column, it.row) }
                    }.associateWith { pos ->
                        require(source.hasChunkAt(pos)) { "Load display backing blocks before transfer" }
                        source.getBlockState(pos).block
                    }
            return AttachedDisplayTransfers(source, destination, positions.toMap(), screens, backing)
        }
    }
}
