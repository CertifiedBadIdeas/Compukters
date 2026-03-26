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

package ck.mod

import ck.mod.gui.ComputerWorkbenchKoolScreen
import ck.mod.gui.GuiSprites
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.server.packs.resources.PreparableReloadListener
import java.util.function.Consumer

object ClientRegistry {
    private var registeredMainThread = false

    fun registerMainThread() {
        if (registeredMainThread) {
            LOGGER.info { "ClientRegistry: registration already completed, skipping duplicate call" }
            return
        }
        try {
            MenuScreens.register(
                ModRegistry.Menus.COMPUTER.get(),
                { container, inventory, title -> ComputerWorkbenchKoolScreen(container, inventory, title) },
            )
            registeredMainThread = true
            LOGGER.info { "ClientRegistry: ComputerWorkbenchKoolScreen successfully registered" }
        } catch (e: Exception) {
            LOGGER.error { "ClientRegistry: ComputerWorkbenchKoolScreen registered with error ${e.message}" }
        }
    }

    fun registerReloadListeners(
        register: Consumer<PreparableReloadListener>,
        minecraft: Minecraft,
    ) {
        register.accept(GuiSprites.initialize(minecraft.getTextureManager()))
    }
}
