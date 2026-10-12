/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.redstone

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.redstone.RedstonePortBlockEntity

class NeoForgeRedstonePortBlockEntity(
    position: BlockPos,
    state: BlockState,
) : RedstonePortBlockEntity(CompuktersRegistry.REDSTONE_PORT_BLOCK_ENTITY.get(), position, state)
