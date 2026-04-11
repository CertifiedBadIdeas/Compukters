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

package ru.lazyhat.compukterkraft.common.block

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.item.ComputerItem
import ru.lazyhat.compukterkraft.core.content.ComputerBlockPolicy

class ComputerBlock(
    properties: Properties,
) : AbstractComputerBlock<ComputerBlockEntity>(properties) {
    private val defaults = ComputerBlockPolicy.defaultState()

    companion object {
        val state: EnumProperty<ComputerState> = EnumProperty.create("state", ComputerState::class.java)
        val facing: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING
    }

    init {
        registerDefaultState(
            defaultBlockState()
                .setValue(facing, defaults.facing.toMinecraftDirection())
                .setValue(state, defaults.state.toMinecraftState()),
        )
    }

    override fun blockEntityType(): BlockEntityType<ComputerBlockEntity> = ModObjects.computerBlockEntityType()

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(facing, state)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(
            facing,
            ComputerBlockPolicy.placementFacing(context.horizontalDirection.toFacingModel()).toMinecraftDirection(),
        )

    override fun getItem(tile: AbstractComputerBlockEntity): ItemStack {
        if (tile !is ComputerBlockEntity) return ItemStack.EMPTY
        return (asItem() as? ComputerItem)?.create(tile.computerID, tile.label) ?: ItemStack.EMPTY
    }
}
