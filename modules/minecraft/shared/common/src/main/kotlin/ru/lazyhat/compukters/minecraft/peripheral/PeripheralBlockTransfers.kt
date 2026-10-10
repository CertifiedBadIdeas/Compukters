/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import ru.lazyhat.compukters.minecraft.display.DisplayBlock
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import ru.lazyhat.compukters.minecraft.display.DisplayStorage
import ru.lazyhat.compukters.minecraft.display.DisplayWorldAccess

/** Explicit block moves preserve membership; ordinary NBT copies retain the live-source safeguards. */
object PeripheralBlockTransfers {
    private val active = ThreadLocal.withInitial { mutableListOf<Transfer>() }

    @JvmStatic
    fun begin(
        source: ServerLevel,
        destination: ServerLevel,
        positions: Map<BlockPos, BlockPos>,
    ): Transfer {
        check(source.server.isSameThread && destination.server === source.server)
        require(positions.values.distinct().size == positions.size) { "Transfer destinations must be distinct" }
        require(source !== destination || positions.keys.none { it in positions.values }) { "Overlapping block transfers are unsupported" }
        val captured =
            positions.mapNotNull { (from, to) ->
                if (!source.hasChunkAt(from)) return@mapNotNull null
                source.getBlockEntity(from)?.let { entity ->
                    if (entity is DisplayBlockEntity && DisplayWorldAccess.surface(source, entity, false) == null) {
                        return@mapNotNull null
                    }
                    val stamp =
                        PeripheralNetworkAccess.instance(entity)?.takeIf { instance ->
                            PeripheralNetworkStorage.get(source).directory.networkOf(instance)?.members?.any {
                                it.instance == instance && it.identity.dimension == source.dimension().toString() &&
                                    it.identity.anchor == from
                            } == true
                        }
                    MovedBlock(entity, from.immutable(), to.immutable(), stamp)
                }
            }
        val screens =
            captured
                .mapNotNull {
                    (it.entity as? DisplayBlockEntity)?.let { entity ->
                        DisplayWorldAccess.surface(source, entity, false)
                    }
                }.distinctBy { it.id }
        for (screen in screens) {
            val movedPanels =
                captured.filter {
                    (it.entity as? DisplayBlockEntity)?.checkpointIdentity in
                        screen.panels.map { it.instance.toString() }
                }
            if (movedPanels.size != screen.panels.size) {
                val name = screen.name
                DisplayWorldAccess.split(source, screen.id)
                if (name != null) {
                    val retained = requireNotNull(DisplayStorage.get(source).directory.byPanel(screen.panels.first().instance))
                    DisplayStorage.get(source).directory.rename(retained.id, name)
                    DisplayNetworkAccess.refresh(source, retained)
                }
            }
        }
        return Transfer(source, destination, captured).also { active.get().add(it) }
    }

    internal fun removing(
        level: ServerLevel,
        position: BlockPos,
    ): Boolean =
        active.get().any {
            it.source === level &&
                it.blocks.any { block ->
                    block.from == position && (
                        level.getBlockEntity(position) === block.entity ||
                            (level.getBlockEntity(position) == null && block.entity.isRemoved)
                    )
                }
        }

    internal fun receiving(
        level: ServerLevel,
        position: BlockPos,
    ): Boolean = active.get().any { it.destination === level && it.blocks.any { block -> block.to == position } }

    internal data class MovedBlock(
        val entity: BlockEntity,
        val from: BlockPos,
        val to: BlockPos,
        val stamp: java.util.UUID?,
    )

    class Transfer internal constructor(
        internal val source: ServerLevel,
        internal val destination: ServerLevel,
        internal val blocks: List<MovedBlock>,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            check(source.server.isSameThread)
            if (closed) return
            closed = true
            try {
                finish()
            } finally {
                active.get().remove(this)
                if (active.get().isEmpty()) active.remove()
            }
        }

        private fun finish() {
            val successful =
                blocks.filter { block ->
                    if (!source.hasChunkAt(block.from) || !destination.hasChunkAt(block.to)) return@filter false
                    val old = source.getBlockEntity(block.from)
                    val next = destination.getBlockEntity(block.to) ?: return@filter false
                    if (old === block.entity || next.type !== block.entity.type) return@filter false
                    if (block.entity is DisplayBlockEntity) {
                        next is DisplayBlockEntity && next.checkpointIdentity == block.entity.checkpointIdentity
                    } else {
                        block.stamp != null && PeripheralNetworkAccess.instance(next) == block.stamp
                    }
                }
            val directory = DisplayStorage.get(source).directory
            var screens =
                blocks
                    .mapNotNull {
                        (it.entity as? DisplayBlockEntity)?.let { entity ->
                            directory.byPanel(java.util.UUID.fromString(entity.checkpointIdentity))
                        }
                    }.distinctBy { it.id }
            for (screen in screens) {
                val count =
                    screen.panels.count { panel ->
                        successful.any { (it.entity as? DisplayBlockEntity)?.checkpointIdentity == panel.instance.toString() }
                    }
                if (count in 1 until screen.panels.size) {
                    DisplayNetworkAccess.checkSplit(source, screen)
                    val replacements = directory.split(screen.id)
                    DisplayNetworkAccess.split(source, screen.id, replacements)
                    screen.name?.let { name ->
                        val retained = replacements.first()
                        directory.rename(retained.id, name)
                        DisplayNetworkAccess.refresh(source, retained)
                    }
                }
            }
            screens =
                blocks
                    .mapNotNull {
                        (it.entity as? DisplayBlockEntity)?.let { entity ->
                            directory.byPanel(java.util.UUID.fromString(entity.checkpointIdentity))
                        }
                    }.distinctBy { it.id }
            for (screen in screens) {
                val moves =
                    screen.panels.mapNotNull { panel ->
                        successful.firstOrNull { (it.entity as? DisplayBlockEntity)?.checkpointIdentity == panel.instance.toString() }
                    }
                if (moves.size != screen.panels.size) continue
                val first = screen.panels.first()
                val movedFirst = moves.first { (it.entity as DisplayBlockEntity).checkpointIdentity == first.instance.toString() }
                val next = destination.getBlockEntity(movedFirst.to) as DisplayBlockEntity
                val facing = next.blockState.getValue(DisplayBlock.FACING)
                val origin = movedFirst.to.relative(facing.counterClockWise, -first.column).above(first.row)
                if (screen.panels.any { panel ->
                        val block = moves.first { (it.entity as DisplayBlockEntity).checkpointIdentity == panel.instance.toString() }
                        block.to != origin.relative(facing.counterClockWise, panel.column).below(panel.row) ||
                            destination.getBlockState(block.to).getValue(DisplayBlock.FACING) != facing
                    }
                ) {
                    continue
                }
                DisplayWorldAccess.relocate(source, destination, screen.id, origin, facing)
            }
            val storage = PeripheralNetworkStorage.get(destination)
            successful.filter { it.entity !is DisplayBlockEntity }.forEach { block ->
                if (storage.directory.relocate(requireNotNull(block.stamp), destination.dimension().toString(), block.to)) {
                    storage.setDirty()
                }
            }
            blocks
                .filter {
                    it.entity !is DisplayBlockEntity && it.stamp != null &&
                        it !in successful && source.hasChunkAt(it.from) && source.getBlockEntity(it.from) !== it.entity
                }.forEach { block ->
                    if (storage.directory.remove(requireNotNull(block.stamp))) storage.setDirty()
                }
            // A failed destination must not revive metadata for an actually destroyed source panel.
            blocks
                .filter {
                    it.entity is DisplayBlockEntity && it !in successful &&
                        source.hasChunkAt(it.from) && source.getBlockEntity(it.from) !== it.entity
                }.forEach { block ->
                    active.get().remove(this)
                    DisplayWorldAccess.remove(source, block.from)
                }
            PeripheralCableTopologyCache.invalidate(source)
            PeripheralCableTopologyCache.invalidate(destination)
        }
    }
}
