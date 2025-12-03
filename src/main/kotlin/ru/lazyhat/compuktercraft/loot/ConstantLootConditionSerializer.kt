// SPDX-FileCopyrightText: 2019 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.loot

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import net.minecraft.world.level.storage.loot.Serializer
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType

class ConstantLootConditionSerializer<T : LootItemCondition>(
    private val instance: T,
) : Serializer<T> {
    companion object {
        fun <T : LootItemCondition> type(condition: T): LootItemConditionType =
            LootItemConditionType(ConstantLootConditionSerializer(condition))
    }

    override fun serialize(
        json: JsonObject,
        `object`: T,
        context: JsonSerializationContext,
    ) {
    }

    override fun deserialize(
        json: JsonObject,
        context: JsonDeserializationContext,
    ): T = instance
}
