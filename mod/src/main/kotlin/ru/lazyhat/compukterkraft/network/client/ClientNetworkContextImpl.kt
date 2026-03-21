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

package ru.lazyhat.compukterkraft.network.client

import net.minecraft.client.Minecraft
import ru.lazyhat.compukterkraft.menu.ComputerMenu
import ru.lazyhat.compukterkraft.network.text.TableBuilder

class ClientNetworkContextImpl : ClientNetworkContext {
    private val minecraft: Minecraft
        get() = Minecraft.getInstance()

    override fun handleChatTable(table: TableBuilder) {
        ClientTableFormatter(minecraft).display(table)
    }

    override fun handleComputerTerminal(
        containerId: Int,
        terminal: ru.lazyhat.compukterkraft.gui.TerminalState,
    ) {
        val menu = minecraft.player?.containerMenu
        if (menu?.containerId != containerId || menu !is ComputerMenu) return
        menu.updateTerminal(terminal)
    }
}
