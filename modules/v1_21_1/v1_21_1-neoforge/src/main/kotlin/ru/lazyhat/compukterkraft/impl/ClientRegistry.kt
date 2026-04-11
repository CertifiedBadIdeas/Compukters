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

import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import ru.lazyhat.compukterkraft.common.gui.screen.ComputerWorkbenchScreen

object ClientRegistry {
    fun register(event: RegisterMenuScreensEvent) {
        try {
            event.register(
                ModRegistry.Menus.COMPUTER.get(),
                { container, inventory, title -> ComputerWorkbenchScreen(container, inventory, title) },
            )
            LOGGER.info { "ClientRegistry: ComputerWorkbenchKoolScreen successfully registered" }
        } catch (e: Exception) {
            LOGGER.error { "ClientRegistry: ComputerWorkbenchKoolScreen registered with error ${e.message}" }
        }
    }
}
