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

package ru.lazyhat.compukterkraft.common.network

import net.minecraft.client.Minecraft
import ru.lazyhat.compukterkraft.common.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.network.text.TableBuilder
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

class ClientNetworkContextImpl : ClientNetworkContext {
    private val minecraft: Minecraft
        get() = Minecraft.getInstance()

    private inline fun withCheckedContainerMenu(
        computerId: Int,
        block: ComputerMenu.() -> Unit,
    ) {
        minecraft
            .player
            ?.containerMenu
            ?.takeIf { it.containerId == computerId }
            ?.let { it as? ComputerMenu }
            ?.run(block)
    }

    override fun handleChatTable(table: TableBuilder) {
        ClientTableFormatter(minecraft).display(table)
    }

    override fun handleComputerTerminal(
        containerId: Int,
        snapshot: ScreenBufferSnapshot,
    ) = withCheckedContainerMenu(containerId) {
        updateTerminal(snapshot)
    }

    override fun handleComputerWorkspaceEntries(
        containerId: Int,
        entries: List<ComputerWorkspaceEntry>,
    ) = withCheckedContainerMenu(containerId) {
        updateWorkspaceEntries(entries)
    }

    override fun handleComputerWorkspaceDocument(
        containerId: Int,
        document: ComputerWorkspaceDocument?,
    ) = withCheckedContainerMenu(containerId) {
        updateWorkspaceDocument(document)
    }
}
