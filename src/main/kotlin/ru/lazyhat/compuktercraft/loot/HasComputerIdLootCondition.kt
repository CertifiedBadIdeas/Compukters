// SPDX-FileCopyrightText: 2020 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.loot

import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.parameters.LootContextParam
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.ModRegistry
import ru.lazyhat.compuktercraft.block.ComputerBlockEntity

/**
 * A loot condition which checks if the block entity has a computer ID.
 */
object HasComputerIdLootCondition : LootItemCondition {
    override fun test(lootContext: LootContext): Boolean {
        CompukterCraftMod.LOGGER.info("HAS ID")
        val tile: BlockEntity? = lootContext.getParamOrNull(LootContextParams.BLOCK_ENTITY)
        return tile is ComputerBlockEntity && tile.computerId != null
    }

    override fun getReferencedContextParams(): Set<LootContextParam<*>> = setOf(LootContextParams.BLOCK_ENTITY)

    override fun getType(): LootItemConditionType = ModRegistry.LootItemConditionTypes.HAS_ID.get()
}
