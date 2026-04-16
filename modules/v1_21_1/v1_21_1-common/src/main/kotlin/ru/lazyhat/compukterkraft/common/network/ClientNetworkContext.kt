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

import ru.lazyhat.compukterkraft.common.network.text.TableBuilder
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * The context under which clientbound packets are evaluated.
 */
interface ClientNetworkContext {
    fun handleChatTable(table: TableBuilder)

    fun handleComputerTerminal(
        containerId: Int,
        snapshot: ScreenBufferSnapshot,
    )

    fun handleComputerWorkspaceEntries(
        containerId: Int,
        entries: List<ComputerWorkspaceEntry>,
    )

    fun handleComputerWorkspaceDocument(
        containerId: Int,
        document: ComputerWorkspaceDocument?,
    )

    fun handleWorkbenchWorkspace(
        containerId: Int,
        remoteState: WorkbenchRemoteState,
    )

    fun handleWorkbenchTerminal(
        containerId: Int,
        snapshot: ScreenBufferSnapshot?,
    )
}
