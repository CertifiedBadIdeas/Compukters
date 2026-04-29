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
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.TextRun
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * The context under which clientbound packets are evaluated.
 */
interface ClientNetworkContext {
    fun handleChatTable(table: TableBuilder)

    fun handleStdoutBytes(
        containerId: Int,
        bytes: ByteArray,
    )

    fun handleWorkbenchWorkspace(
        containerId: Int,
        remoteState: WorkbenchRemoteState,
    )

    fun handleWorkbenchTerminal(
        containerId: Int,
        snapshot: ScreenBufferSnapshot?,
    )

    fun handleWorkbenchOps(
        containerId: Int,
        path: String,
        ops: List<Op>,
        ackedClock: Int,
    )

    fun handleWorkbenchDocumentSnapshot(
        containerId: Int,
        path: String,
        runs: List<TextRun>,
        versionVector: Map<SiteId, Int>,
    )

    fun handleWorkbenchPresence(
        containerId: Int,
        presences: List<ru.lazyhat.compukterkraft.core.computer.workbench.EditorPresence>,
    )

    fun handleWorkbenchCursor(
        containerId: Int,
        path: String,
        siteId: SiteId,
        cursor: ru.lazyhat.compukterkraft.core.computer.workbench.crdt.CursorAnchor?,
    )
}
