/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.integration.createaddition

import net.neoforged.fml.common.Mod

@Mod(CompuktersCreateadditionMod.MOD_ID)
class CompuktersCreateadditionMod {
    init {
        LiquidBurnerIntegration.register()
    }

    companion object {
        const val MOD_ID = "compukters_createaddition"
    }
}
