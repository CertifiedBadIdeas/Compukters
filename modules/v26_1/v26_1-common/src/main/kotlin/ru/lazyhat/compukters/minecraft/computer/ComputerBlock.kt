/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import java.util.function.Supplier

class ComputerBlock(
    properties: BlockBehaviour.Properties,
    private val factory: (BlockPos, BlockState) -> ComputerBlockEntity,
    private val blockEntityType: Supplier<out BlockEntityType<out ComputerBlockEntity>>,
    private val terminalOpener: (ServerPlayer, ComputerBlockEntity) -> Unit,
) : Block(properties),
    EntityBlock {
    override fun newBlockEntity(
        position: BlockPos,
        blockState: BlockState,
    ): ComputerBlockEntity = factory(position, blockState)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        blockState: BlockState,
        actualType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = computerTickerFor(level.isClientSide, actualType, blockEntityType.get())

    override fun useWithoutItem(
        blockState: BlockState,
        level: Level,
        position: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val entity = level.getBlockEntity(position) as? ComputerBlockEntity ?: return InteractionResult.PASS
        terminalOpener(serverPlayer, entity)
        return InteractionResult.SUCCESS_SERVER
    }
}

internal fun <T : BlockEntity> computerTickerFor(
    isClientSide: Boolean,
    actualType: BlockEntityType<T>,
    expectedType: BlockEntityType<out ComputerBlockEntity>,
): BlockEntityTicker<T>? {
    if (isClientSide || actualType !== expectedType) return null
    return BlockEntityTicker { _, _, _, entity ->
        tickComputerEntity(entity as ComputerBlockEntity)
    }
}

internal fun tickComputerEntity(entity: ComputerBlockEntity) = entity.serverTick()
