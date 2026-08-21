/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.common.utils

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker

fun BlockEntity.updateBlock() {
    setChanged()
    level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_ALL)
}

@Suppress("UNCHECKED_CAST")
fun <A : BlockEntity, B : BlockEntity> BlockEntityTicker<A>.castTicker(): BlockEntityTicker<B>? = (this as? BlockEntityTicker<B>)
