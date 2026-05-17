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

package ru.lazyhat.compukterkraft.impl

import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.impl.notebook.render.NotebookBlockEntityRenderer

@EventBusSubscriber(modid = MOD_ID, value = [Dist.CLIENT])
object ForgeClientRegistry {
    @SubscribeEvent
    @JvmStatic
    fun onRegisterMenuScreens(event: RegisterMenuScreensEvent) {
        ClientRegistry.register(event)
    }

    @SubscribeEvent
    @JvmStatic
    fun onRegisterBlockEntityRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModRegistry.BlockEntities.NOTEBOOK.get(), ::NotebookBlockEntityRenderer)
    }
}
