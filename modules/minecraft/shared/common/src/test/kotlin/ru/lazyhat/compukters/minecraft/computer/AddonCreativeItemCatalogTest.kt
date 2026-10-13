/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.world.level.ItemLike
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddonCreativeItemCatalogTest {
    @Test
    fun `providers are lazy and groups have stable order without duplicate items`() {
        val first = ItemLike { error("No item materialization is needed") }
        val second = ItemLike { error("No item materialization is needed") }
        val catalog = AddonCreativeItemCatalog()
        var calls = 0
        catalog.register("zeta") {
            calls++
            listOf(second, first)
        }
        catalog.register("alpha") {
            calls++
            listOf(first)
        }
        catalog.register("empty") {
            calls++
            emptyList()
        }
        assertEquals(0, calls)
        assertEquals(listOf(first, second), catalog.items())
        assertEquals(3, calls)
        assertEquals(listOf(first, second), catalog.items())
        assertEquals(6, calls)
    }

    @Test
    fun `invalid and duplicate groups are rejected without invoking providers`() {
        val catalog = AddonCreativeItemCatalog()
        val provider = java.util.function.Supplier<List<ItemLike>> { error("Registration must be lazy") }
        for (id in listOf("", "Create", "create:items", "a".repeat(65))) {
            assertFailsWith<IllegalArgumentException> { catalog.register(id, provider) }
        }
        catalog.register("create", provider)
        assertFailsWith<IllegalArgumentException> { catalog.register("create", provider) }
    }
}
