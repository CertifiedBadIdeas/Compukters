package ru.lazyhat.compukterkraft.core.content

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LootConditionPolicyTest {
    @Test
    fun reportsPresenceOfStoredIdentity() {
        assertFalse(LootConditionPolicy.hasComputerId(null))
        assertTrue(LootConditionPolicy.hasComputerId(42))
    }

    @Test
    fun reportsPresenceOfCustomName() {
        assertFalse(LootConditionPolicy.hasCustomName(false))
        assertTrue(LootConditionPolicy.hasCustomName(true))
    }

    @Test
    fun reportsCreativeModeFromBooleanCapability() {
        assertFalse(LootConditionPolicy.isCreativePlayer(false))
        assertTrue(LootConditionPolicy.isCreativePlayer(true))
    }
}