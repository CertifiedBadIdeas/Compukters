/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.computer

import net.minecraft.world.level.ItemLike
import java.util.TreeMap
import java.util.function.Supplier

/** One creative inventory, with base items followed by stable addon groups. */
object ComputerAddonCreativeItems {
    private val catalog = AddonCreativeItemCatalog()

    fun register(
        addonId: String,
        items: Supplier<List<ItemLike>>,
    ) = catalog.register(addonId, items)

    fun items(): List<ItemLike> = catalog.items()
}

internal class AddonCreativeItemCatalog {
    private val groups = TreeMap<String, Supplier<List<ItemLike>>>()

    @Synchronized
    fun register(
        addonId: String,
        items: Supplier<List<ItemLike>>,
    ) {
        require(addonId.length in 1..64 && addonId.matches(Regex("[a-z0-9_.-]+"))) { "Invalid creative addon ID" }
        require(addonId !in groups) { "Creative addon group is already registered: $addonId" }
        require(groups.size < 256) { "Creative addon group limit exceeded" }
        groups[addonId] = items
    }

    fun items(): List<ItemLike> {
        val providers = synchronized(this) { groups.values.toList() }
        return providers.flatMap { it.get().toList() }.distinct()
    }
}
