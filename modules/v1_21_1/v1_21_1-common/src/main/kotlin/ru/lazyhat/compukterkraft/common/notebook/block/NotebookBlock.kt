/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.common.notebook.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlock
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.block.ComputerBlock
import ru.lazyhat.compukterkraft.common.computer.block.ComputerState
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.notebook.item.NotebookItem

class NotebookBlock(
    properties: Properties,
) : AbstractComputerBlock<NotebookBlockEntity>(properties) {
    companion object {
        private val CODEC: MapCodec<NotebookBlock> = simpleCodec(::NotebookBlock)
    }

    init {
        registerDefaultState(
            defaultBlockState()
                .setValue(ComputerBlock.facing, Direction.NORTH)
                .setValue(ComputerBlock.state, ComputerState.OFF),
        )
    }

    override fun blockEntityType(): BlockEntityType<out NotebookBlockEntity> = ModObjects.notebookBlockEntityType()

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(ComputerBlock.facing, ComputerBlock.state)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(ComputerBlock.facing, context.horizontalDirection.opposite)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    override fun codec(): MapCodec<out NotebookBlock> = CODEC

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true)
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val tile = level.getBlockEntity(pos) as? NotebookBlockEntity ?: return InteractionResult.PASS
        val device = tile.getOrCreateRuntimeDevice()

        ModObjects.openComputerMenu(
            serverPlayer,
            tile,
            ComputerContainerData(device, getItem(tile)),
        )
        return InteractionResult.sidedSuccess(false)
    }

    override fun getItem(tile: AbstractComputerBlockEntity): ItemStack {
        if (tile !is NotebookBlockEntity) return ItemStack.EMPTY
        return (asItem() as? NotebookItem)?.create(tile.computerID, tile.label) ?: ItemStack.EMPTY
    }
}
