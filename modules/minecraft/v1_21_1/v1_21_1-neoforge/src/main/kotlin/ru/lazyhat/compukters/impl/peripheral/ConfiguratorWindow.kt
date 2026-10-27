/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

internal object ConfiguratorWindow {
    fun handle(): Long =
        net.minecraft.client.Minecraft
            .getInstance()
            .window.window
}
