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

package ck.mod.block

import ck.mod.item.ComputerItem
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import java.util.function.Supplier

class ComputerBlock(
    val type: Supplier<BlockEntityType<ComputerBlockEntity>>,
    properties: Properties,
) : AbstractComputerBlock<ComputerBlockEntity>(type, properties) {
    companion object {
        val state: EnumProperty<ComputerState> = EnumProperty.create("state", ComputerState::class.java)
        val facing: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING
    }

    init {
        registerDefaultState(
            defaultBlockState()
                .setValue(facing, Direction.NORTH)
                .setValue(state, ComputerState.OFF),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(facing, state)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(facing, context.horizontalDirection.opposite)

    override fun getItem(tile: AbstractComputerBlockEntity): ItemStack {
        if (tile !is ComputerBlockEntity) return ItemStack.EMPTY
        return (asItem() as? ComputerItem)?.create(tile.computerID, tile.label) ?: ItemStack.EMPTY
    }
}
