/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import ru.lazyhat.compukters.minecraft.computer.ComputerAddonHosts
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock

class PeripheralCableBlock(
    properties: BlockBehaviour.Properties,
) : Block(properties) {
    init {
        registerDefaultState(
            stateDefinition
                .any()
                .setValue(DOWN, false)
                .setValue(UP, false)
                .setValue(NORTH, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(EAST, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(DOWN, UP, NORTH, SOUTH, WEST, EAST)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        connectedState(defaultBlockState(), context.level, context.clickedPos)

    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        tickAccess: ScheduledTickAccess,
        position: BlockPos,
        direction: Direction,
        neighborPosition: BlockPos,
        neighborState: BlockState,
        random: RandomSource,
    ): BlockState =
        state.setValue(
            PROPERTY_BY_DIRECTION.getValue(direction),
            connectsTo(level, neighborPosition, direction, neighborState, state.getValue(PROPERTY_BY_DIRECTION.getValue(direction))),
        )

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        position: BlockPos,
        context: CollisionContext,
    ): VoxelShape = shapeFor(state)

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

    override fun affectNeighborsAfterRemoval(
        state: BlockState,
        level: ServerLevel,
        position: BlockPos,
        movedByPiston: Boolean,
    ) {
        super.affectNeighborsAfterRemoval(state, level, position, movedByPiston)
        PeripheralCableTopologyCache.invalidate(level)
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        position: BlockPos,
        neighborBlock: Block,
        orientation: Orientation?,
        movedByPiston: Boolean,
    ) {
        super.neighborChanged(state, level, position, neighborBlock, orientation, movedByPiston)
        PeripheralCableTopologyCache.invalidate(level)
    }

    private fun connectedState(
        initialState: BlockState,
        level: LevelReader,
        position: BlockPos,
    ): BlockState =
        Direction.entries.fold(initialState) { state, direction ->
            val neighborPosition = position.relative(direction)
            state.setValue(
                PROPERTY_BY_DIRECTION.getValue(direction),
                connectsTo(level, neighborPosition, direction, level.getBlockState(neighborPosition), false),
            )
        }

    private fun connectsTo(
        level: BlockGetter,
        neighborPosition: BlockPos,
        direction: Direction,
        neighborState: BlockState,
        clientFallback: Boolean,
    ): Boolean {
        if (PeripheralCableBlocks.contains(neighborState) || neighborState.block is ComputerBlock) return true
        val serverLevel = level as? ServerLevel ?: return clientFallback
        return ComputerAddonHosts.resolvePeripheralContact(serverLevel, neighborPosition, direction.opposite).isNotEmpty()
    }

    companion object {
        val DOWN: BooleanProperty = BlockStateProperties.DOWN
        val UP: BooleanProperty = BlockStateProperties.UP
        val NORTH: BooleanProperty = BlockStateProperties.NORTH
        val SOUTH: BooleanProperty = BlockStateProperties.SOUTH
        val WEST: BooleanProperty = BlockStateProperties.WEST
        val EAST: BooleanProperty = BlockStateProperties.EAST

        private val PROPERTY_BY_DIRECTION =
            mapOf(
                Direction.DOWN to DOWN,
                Direction.UP to UP,
                Direction.NORTH to NORTH,
                Direction.SOUTH to SOUTH,
                Direction.WEST to WEST,
                Direction.EAST to EAST,
            )
        private val CENTER = box(5.0, 5.0, 5.0, 11.0, 11.0, 11.0)
        private val ARM_BY_DIRECTION =
            mapOf(
                Direction.DOWN to box(5.0, 0.0, 5.0, 11.0, 5.0, 11.0),
                Direction.UP to box(5.0, 11.0, 5.0, 11.0, 16.0, 11.0),
                Direction.NORTH to box(5.0, 5.0, 0.0, 11.0, 11.0, 5.0),
                Direction.SOUTH to box(5.0, 5.0, 11.0, 11.0, 11.0, 16.0),
                Direction.WEST to box(0.0, 5.0, 5.0, 5.0, 11.0, 11.0),
                Direction.EAST to box(11.0, 5.0, 5.0, 16.0, 11.0, 11.0),
            )

        private fun shapeFor(state: BlockState): VoxelShape =
            Direction.entries.fold(CENTER) { shape, direction ->
                if (state.getValue(PROPERTY_BY_DIRECTION.getValue(direction))) {
                    Shapes.or(shape, ARM_BY_DIRECTION.getValue(direction))
                } else {
                    shape
                }
            }
    }
}
