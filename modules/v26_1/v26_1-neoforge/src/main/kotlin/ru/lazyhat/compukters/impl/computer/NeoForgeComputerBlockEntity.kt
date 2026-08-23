/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity

class NeoForgeComputerBlockEntity(
    position: BlockPos,
    blockState: BlockState,
) : ComputerBlockEntity(CompuktersRegistry.COMPUTER_BLOCK_ENTITY.get(), position, blockState)
