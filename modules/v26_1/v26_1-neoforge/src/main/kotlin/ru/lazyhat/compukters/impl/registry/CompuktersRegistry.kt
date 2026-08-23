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

package ru.lazyhat.compukters.impl.registry

import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.computer.NeoForgeComputerBlockEntity
import ru.lazyhat.compukters.minecraft.computer.ComputerBlock
import java.util.function.Supplier

object CompuktersRegistry {
    private val blocks = DeferredRegister.createBlocks(MOD_ID)
    private val items = DeferredRegister.createItems(MOD_ID)
    private val blockEntities = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID)

    val COMPUTER: DeferredBlock<ComputerBlock> =
        blocks.register(
            "compukter",
            Supplier {
                ComputerBlock(
                    BlockBehaviour.Properties.of().strength(2.0f),
                    ::NeoForgeComputerBlockEntity,
                    Supplier { COMPUTER_BLOCK_ENTITY.get() },
                )
            },
        )

    val COMPUTER_ITEM: DeferredItem<BlockItem> = items.registerSimpleBlockItem(COMPUTER)

    val COMPUTER_BLOCK_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<NeoForgeComputerBlockEntity>> =
        blockEntities.register(
            "compukter",
            Supplier {
                BlockEntityType.Builder
                    .of(::NeoForgeComputerBlockEntity, COMPUTER.get())
                    .build(null)
            },
        )

    fun register(eventBus: IEventBus) {
        blocks.register(eventBus)
        items.register(eventBus)
        blockEntities.register(eventBus)
    }
}
