/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.redstone

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import ru.lazyhat.compukters.core.redstone.RedstonePortState
import ru.lazyhat.compukters.minecraft.computer.LocalRedstoneSide
import ru.lazyhat.compukters.minecraft.computer.localSide
import ru.lazyhat.compukters.minecraft.computer.worldDirection

/** No output or ownership data enters NBT; loaded/replaced ports always start at zero. */
open class RedstonePortBlockEntity(
    type: BlockEntityType<*>,
    position: BlockPos,
    state: BlockState,
) : BlockEntity(type, position, state) {
    val signals =
        RedstonePortState { mask ->
            val server = level as? ServerLevel
            if (server != null && server.hasChunkAt(blockPos) && server.getBlockEntity(blockPos) === this) {
                notifyRedstonePortNeighbors(server, blockPos, blockState, mask)
            }
        }

    fun output(direction: Direction): Int =
        signals.output(localSide(blockState.getValue(BlockStateProperties.HORIZONTAL_FACING), direction).ordinal)

    fun serverTick() {
        val server = level as? ServerLevel ?: return
        check(server.server.isSameThread)
        signals.expire()
        val facing = blockState.getValue(BlockStateProperties.HORIZONTAL_FACING)
        signals.sample(
            IntArray(6) { side ->
                val direction = worldDirection(facing, LocalRedstoneSide.entries[side])
                server.getSignal(blockPos.relative(direction), direction)
            },
        )
    }

    override fun setRemoved() {
        signals.reset()
        super.setRemoved()
    }
}
