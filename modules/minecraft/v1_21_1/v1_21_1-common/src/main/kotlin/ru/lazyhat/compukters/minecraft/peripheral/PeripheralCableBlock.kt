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
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

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

    override fun onRemove(
        state: BlockState,
        level: Level,
        position: BlockPos,
        newState: BlockState,
        movedByPiston: Boolean,
    ) {
        super.onRemove(state, level, position, newState, movedByPiston)
        PeripheralCableTopologyCache.invalidate(level)
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        position: BlockPos,
        neighborBlock: Block,
        neighborPosition: BlockPos,
        movedByPiston: Boolean,
    ) {
        super.neighborChanged(state, level, position, neighborBlock, neighborPosition, movedByPiston)
        PeripheralCableTopologyCache.invalidate(level)
    }
}
