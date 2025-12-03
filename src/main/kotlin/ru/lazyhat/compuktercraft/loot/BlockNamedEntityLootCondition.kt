// SPDX-FileCopyrightText: 2019 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.loot

import net.minecraft.world.Nameable
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.parameters.LootContextParam
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
import ru.lazyhat.compuktercraft.ModRegistry

/**
 * A loot condition which checks if the block entity has a name.
 */
object BlockNamedEntityLootCondition : LootItemCondition {
    override fun test(lootContext: LootContext): Boolean =
        lootContext.getParamOrNull(LootContextParams.BLOCK_ENTITY)?.let { tile ->
            tile is Nameable && tile.hasCustomName()
        } ?: false

    override fun getReferencedContextParams(): Set<LootContextParam<*>> = setOf(LootContextParams.BLOCK_ENTITY)

    override fun getType(): LootItemConditionType = ModRegistry.LootItemConditionTypes.BLOCK_NAMED.get()
}
