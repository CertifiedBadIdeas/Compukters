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
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.redstone.Orientation

class PeripheralCableBlock(
    properties: BlockBehaviour.Properties,
) : Block(properties) {
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
}
