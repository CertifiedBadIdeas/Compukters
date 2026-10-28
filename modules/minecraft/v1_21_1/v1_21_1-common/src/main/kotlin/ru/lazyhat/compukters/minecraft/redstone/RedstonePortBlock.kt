/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.redstone

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
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
import ru.lazyhat.compukters.lang.runtime.vm.RedstoneWire
import ru.lazyhat.compukters.minecraft.computer.LocalRedstoneSide
import ru.lazyhat.compukters.minecraft.computer.worldDirection
import java.util.function.Supplier

class RedstonePortBlock(
    properties: BlockBehaviour.Properties,
    private val factory: (BlockPos, BlockState) -> RedstonePortBlockEntity,
    private val type: Supplier<out BlockEntityType<out RedstonePortBlockEntity>>,
) : Block(properties.isRedstoneConductor { _, _, _ -> false }),
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
        state: BlockState,
        rotation: Rotation,
    ): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(
        state: BlockState,
        mirror: Mirror,
    ): BlockState = state.setValue(FACING, mirror.mirror(state.getValue(FACING)))

    override fun newBlockEntity(
        position: BlockPos,
        state: BlockState,
    ): BlockEntity = factory(position, state)

    override fun isSignalSource(state: BlockState): Boolean = true

    override fun getSignal(
        state: BlockState,
        level: BlockGetter,
        position: BlockPos,
        direction: Direction,
    ): Int = ((level.getBlockEntity(position) as? RedstonePortBlockEntity)?.output(direction.opposite) ?: 0) and RedstoneWire.SIGNAL_MASK

    override fun getDirectSignal(
        state: BlockState,
        level: BlockGetter,
        position: BlockPos,
        direction: Direction,
    ): Int {
        val output = (level.getBlockEntity(position) as? RedstonePortBlockEntity)?.output(direction.opposite) ?: 0
        return if (output and RedstoneWire.DIRECT_MASK != 0) output and RedstoneWire.SIGNAL_MASK else 0
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        actual: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide ||
            actual != type.get()
        ) {
            null
        } else {
            BlockEntityTicker { _, _, _, entity -> (entity as? RedstonePortBlockEntity)?.serverTick() }
        }

    override fun onPlace(
        state: BlockState,
        level: Level,
        position: BlockPos,
        previous: BlockState,
        movedByPiston: Boolean,
    ) {
        super.onPlace(state, level, position, previous, movedByPiston)
        if (!level.isClientSide && previous.block === this && previous.getValue(FACING) != state.getValue(FACING)) {
            (level.getBlockEntity(position) as? RedstonePortBlockEntity)?.signals?.reset()
            if (level is ServerLevel) notifyRedstonePortNeighbors(level, position, state, 63)
        }
    }

    companion object {
        val FACING = BlockStateProperties.HORIZONTAL_FACING
    }
}

internal fun notifyRedstonePortNeighbors(
    level: ServerLevel,
    position: BlockPos,
    state: BlockState,
    mask: Int,
) {
    LocalRedstoneSide.entries.forEach { side ->
        if (mask and (1 shl side.ordinal) != 0) {
            val direction = worldDirection(state.getValue(RedstonePortBlock.FACING), side)
            val neighbor = position.relative(direction)
            level.neighborChanged(neighbor, state.block, position)
            // Strong output powers the conductor itself. Devices attached to its other
            // faces must also observe changes, including strong-to-weak and release.
            if (level.getBlockState(neighbor).isRedstoneConductor(level, neighbor)) {
                level.updateNeighborsAt(neighbor, state.block)
            }
        }
    }
}
