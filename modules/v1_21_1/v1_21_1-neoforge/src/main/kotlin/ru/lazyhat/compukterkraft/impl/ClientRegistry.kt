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
import ru.lazyhat.compukterkraft.common.computer.screen.ComputerControlScreen
import ru.lazyhat.compukterkraft.common.serial.screen.SerialTerminalScreen
import ru.lazyhat.compukterkraft.common.terminal.screen.ComputerTerminalScreen
import ru.lazyhat.compukterkraft.common.workbench.screen.WorkbenchEditorScreen
import ru.lazyhat.compukterkraft.core.LOGGER

object ClientRegistry {
    fun register(event: RegisterMenuScreensEvent) {
        try {
            event.register(
                ModRegistry.Menus.COMPUTER.get(),
                { container, inventory, title -> ComputerTerminalScreen(container, inventory, title) },
            )
            event.register(
                ModRegistry.Menus.WORKBENCH.get(),
                { container, inventory, title -> WorkbenchEditorScreen(container, inventory, title) },
            )
            event.register(
                ModRegistry.Menus.COMPUTER_CONTROL.get(),
                { container, inventory, title -> ComputerControlScreen(container, inventory, title) },
            )
            event.register(
                ModRegistry.Menus.SERIAL_TERMINAL.get(),
                { container, inventory, title -> SerialTerminalScreen(container, inventory, title) },
            )
            LOGGER.debug { "ClientRegistry: terminal-only computer screen successfully registered" }
        } catch (e: Exception) {
            LOGGER.error { "ClientRegistry: computer screen registration failed: ${e.message}" }
        }
    }
}
