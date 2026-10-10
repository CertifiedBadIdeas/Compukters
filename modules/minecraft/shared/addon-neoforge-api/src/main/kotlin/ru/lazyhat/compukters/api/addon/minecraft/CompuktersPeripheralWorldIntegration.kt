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
    @JvmStatic
    fun install(
        project: (Level, Vec3) -> Vec3?,
        nearby: (ServerLevel, Vec3, Double) -> Sequence<BlockEntity>,
    ) {
        PeripheralWorldPositions.install(project, nearby)
    }

    /** Close in finally after the complete batch, including source destruction, on the server thread. */
    @JvmStatic
    fun beginTransfer(
        source: ServerLevel,
        destination: ServerLevel,
        positions: Map<BlockPos, BlockPos>,
    ): AutoCloseable = PeripheralBlockTransfers.begin(source, destination, positions)
}
