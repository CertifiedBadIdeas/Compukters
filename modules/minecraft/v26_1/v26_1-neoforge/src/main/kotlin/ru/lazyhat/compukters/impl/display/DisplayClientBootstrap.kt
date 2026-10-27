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
        ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity.requestSnapshot = { entity ->
            net.minecraft.client.Minecraft.getInstance().connection?.send(
                net.minecraft.network.protocol.common
                    .ServerboundCustomPayloadPacket(DisplaySnapshotRequest(entity.blockPos.asLong())),
            )
        }
        AttachedDisplayRenderer.register()
        eventBus.addListener(::registerRenderers)
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
            .addListener(::onTick)
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
            .addListener(DisplayPointerClient::onMouse)
    }

    private fun onTick(
        @Suppress("UNUSED_PARAMETER") event: net.neoforged.neoforge.client.event.ClientTickEvent.Post,
    ) {
        AttachedDisplayClient.tick()
        DisplayTextureCache.cleanup()
        DisplayPointerClient.tick()
    }

    private fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(CompuktersRegistry.DISPLAY_BLOCK_ENTITY.get(), ::DisplayBlockEntityRenderer)
    }
}
