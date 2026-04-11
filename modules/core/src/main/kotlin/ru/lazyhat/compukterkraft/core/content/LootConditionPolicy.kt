package ru.lazyhat.compukterkraft.core.content

object LootConditionPolicy {
    fun hasComputerId(computerId: Int?): Boolean = computerId != null

    fun hasCustomName(hasCustomName: Boolean): Boolean = hasCustomName

    fun isCreativePlayer(hasInstabuildAbility: Boolean): Boolean = hasInstabuildAbility
}