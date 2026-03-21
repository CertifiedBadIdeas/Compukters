/*
 * The Compukter Kraft Developers
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
package ru.lazyhat.compukterkraft.loot

import net.minecraft.world.Nameable
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.parameters.LootContextParam
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
import ru.lazyhat.compukterkraft.ModRegistry

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
