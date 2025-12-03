// SPDX-FileCopyrightText: 2019 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.loot

import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.parameters.LootContextParam
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
import ru.lazyhat.compuktercraft.ModRegistry

/**
 * A loot condition which checks if the entity is in creative mode.
 */
object PlayerCreativeLootCondition : LootItemCondition {
    override fun test(lootContext: LootContext): Boolean =
        lootContext.getParamOrNull(LootContextParams.THIS_ENTITY)?.let { entity ->
            entity is Player && entity.abilities.instabuild
        } ?: false

    override fun getReferencedContextParams(): Set<LootContextParam<*>> = setOf(LootContextParams.THIS_ENTITY)

    override fun getType(): LootItemConditionType = ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE.get()
}
