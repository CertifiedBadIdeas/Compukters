/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralCableTopologyCache
import java.util.function.Supplier

class DisplayBlock(
    properties: BlockBehaviour.Properties,
    private val factory: (BlockPos, BlockState) -> DisplayBlockEntity,
    private val blockEntityType: Supplier<out BlockEntityType<out DisplayBlockEntity>>,
) : Block(properties),
    EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(
        blockState: BlockState,
        rotation: Rotation,
    ): BlockState = blockState.setValue(FACING, rotation.rotate(blockState.getValue(FACING)))

    override fun mirror(
        blockState: BlockState,
        mirror: Mirror,
    ): BlockState = blockState.setValue(FACING, mirror.mirror(blockState.getValue(FACING)))

    override fun newBlockEntity(
        position: BlockPos,
        blockState: BlockState,
    ): DisplayBlockEntity = factory(position, blockState)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        blockState: BlockState,
        actualType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide || actualType != blockEntityType.get()) {
            null
        } else {
            BlockEntityTicker { _, _, _, entity -> (entity as? DisplayBlockEntity)?.serverTick() }
        }

    override fun onPlace(
        state: BlockState,
        level: Level,
        position: BlockPos,
        oldState: BlockState,
        movedByPiston: Boolean,
    ) {
        super.onPlace(state, level, position, oldState, movedByPiston)
        PeripheralCableTopologyCache.invalidate(level)
    }

    override fun onRemove(
        state: BlockState,
        level: Level,
        position: BlockPos,
        newState: BlockState,
        movedByPiston: Boolean,
    ) {
        super.onRemove(state, level, position, newState, movedByPiston)
        if (state.block !== newState.block) PeripheralCableTopologyCache.invalidate(level)
    }

    companion object {
        val FACING = BlockStateProperties.HORIZONTAL_FACING
    }
}
