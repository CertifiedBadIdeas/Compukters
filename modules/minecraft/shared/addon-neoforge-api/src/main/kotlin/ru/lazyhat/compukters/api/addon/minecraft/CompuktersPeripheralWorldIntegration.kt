/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.api.addon.minecraft

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralBlockTransfers
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralWorldPositions

/** Optional construction adapter. Implementations must query loaded state without forcing chunks. */
object CompuktersPeripheralWorldIntegration {
    interface Recovery : AutoCloseable {
        /** Reconcile only after the coordinator has removed obsolete copies. */
        fun resolve()
    }

    @JvmStatic
    fun install(
        project: (Level, Vec3) -> Vec3?,
        nearby: (ServerLevel, Vec3, Double) -> Sequence<BlockEntity>,
    ) {
        PeripheralWorldPositions.install(project, nearby)
    }

    /** Call before serializing source blocks. Checkpoint publication stays asynchronous. */
    @JvmStatic
    fun prepareTransfer(
        source: ServerLevel,
        positions: Iterable<BlockPos>,
    ) {
        prepareTransferAsync(source, positions)
    }

    /** Await without blocking the server thread, before serializing or changing source ownership. */
    @JvmStatic
    fun prepareTransferAsync(
        source: ServerLevel,
        positions: Iterable<BlockPos>,
    ): java.util.concurrent.CompletableFuture<Void> {
        check(source.server.isSameThread)
        val completions =
            positions.mapNotNull { position ->
                if (!source.hasChunkAt(position)) return@mapNotNull null
                (source.getBlockEntity(position) as? ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity)
                    ?.prepareBlockTransferAsync()
            }
        return java.util.concurrent.CompletableFuture
            .allOf(*completions.toTypedArray())
    }

    /** Prevent copied blocks from attaching computers or changing membership during reconciliation. */
    @JvmStatic
    fun guardTransfer(
        level: ServerLevel,
        positions: Iterable<BlockPos>,
    ): AutoCloseable = PeripheralBlockTransfers.guard(level, positions)

    /** Keep computer admission suspended after metadata resolution, until the durable transaction completes. */
    @JvmStatic
    fun guardComputers(
        level: ServerLevel,
        positions: Iterable<BlockPos>,
    ): AutoCloseable = PeripheralBlockTransfers.guard(level, positions, metadata = false)

    /** Both saved copies must be guarded until journal authority has been resolved. */
    @JvmStatic
    fun recoverTransfer(
        source: ServerLevel,
        destination: ServerLevel,
        positions: Map<BlockPos, BlockPos>,
        committed: Boolean,
    ): Recovery {
        val delegate = PeripheralBlockTransfers.recover(source, destination, positions, committed)
        return object : Recovery {
            override fun resolve() = delegate.resolve()

            override fun close() = delegate.close()
        }
    }

    /** Close in finally after the complete batch, including source destruction, on the server thread. */
    @JvmStatic
    fun beginTransfer(
        source: ServerLevel,
        destination: ServerLevel,
        positions: Map<BlockPos, BlockPos>,
    ): AutoCloseable = PeripheralBlockTransfers.begin(source, destination, positions)

    /**
     * Before permanently deleting bulk block storage, retire displays on those backing blocks.
     * The predicate must inspect loaded ownership only. Do not call for chunk/construction unload.
     * Active transfer guards retain their authority over source removal.
     */
    @JvmStatic
    fun removeBlocks(
        level: ServerLevel,
        removed: (BlockPos) -> Boolean,
    ) = PeripheralBlockTransfers.removeBlocks(level, removed)
}
