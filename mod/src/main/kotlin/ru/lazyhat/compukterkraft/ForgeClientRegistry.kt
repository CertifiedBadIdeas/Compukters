/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft

import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.PreparableReloadListener
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = MOD_ID, value = [Dist.CLIENT], bus = Mod.EventBusSubscriber.Bus.MOD)
object ForgeClientRegistry {
    init {
        LOGGER.info { "ForgeClientRegistry init" }
    }

    @SubscribeEvent
    @JvmStatic
    fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        LOGGER.info { "ForgeClientRegistry registerReloadListeners" }
        ClientRegistry.registerReloadListeners(
            { reloadListener: PreparableReloadListener ->
                event.registerReloadListener(reloadListener)
            },
            Minecraft.getInstance(),
        )
    }
}
