/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent
import ru.lazyhat.compukters.core.MOD_ID

@EventBusSubscriber(modid = MOD_ID, value = [Dist.CLIENT])
object TerminalClientNetwork {
    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterClientPayloadHandlersEvent) {
        event.register(TerminalSnapshotPayload.TYPE) { payload, _ ->
            val minecraft = Minecraft.getInstance()
            val current = minecraft.screen
            if (current is TerminalScreen && current.position == payload.position) {
                current.update(payload)
            } else if (payload.openScreen) {
                minecraft.setScreen(TerminalScreen(payload))
            }
        }
    }
}
