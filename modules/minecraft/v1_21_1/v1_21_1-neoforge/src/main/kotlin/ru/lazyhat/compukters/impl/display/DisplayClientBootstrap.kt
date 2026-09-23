/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry

object DisplayClientBootstrap {
    fun register(eventBus: IEventBus) {
        eventBus.addListener(::registerRenderers)
    }

    private fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(CompuktersRegistry.DISPLAY_BLOCK_ENTITY.get(), ::DisplayBlockEntityRenderer)
    }
}
