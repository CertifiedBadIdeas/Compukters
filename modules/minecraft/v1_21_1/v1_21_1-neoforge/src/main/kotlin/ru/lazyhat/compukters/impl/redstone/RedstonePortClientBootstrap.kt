/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.redstone

import net.minecraft.core.Direction
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.redstone.RedstonePortBlockEntity

/** Each tint index belongs to one world face, including all indicators drawn on that face. */
object RedstonePortClientBootstrap {
    fun register(eventBus: IEventBus) {
        eventBus.addListener(::registerColors)
    }

    private fun color(level: Int): Int {
        val brightness = 64 + level * 191 / 15
        return -0x1000000 or (brightness shl 16) or (brightness shl 8) or brightness
    }

    private fun registerColors(event: RegisterColorHandlersEvent.Block) {
        event.register(
            { _, level, position, index ->
                val direction = Direction.entries.getOrNull(index)
                color(
                    if (direction != null && level != null && position != null) {
                        (level.getBlockEntity(position) as? RedstonePortBlockEntity)?.indicatorLevel(direction) ?: 0
                    } else {
                        0
                    },
                )
            },
            CompuktersRegistry.REDSTONE_PORT.get(),
        )
    }
}
