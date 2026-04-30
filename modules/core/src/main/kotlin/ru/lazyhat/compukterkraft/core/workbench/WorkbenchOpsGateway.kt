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
package ru.lazyhat.compukterkraft.core.workbench

import ru.lazyhat.compukterkraft.core.workbench.crdt.CursorAnchor
import ru.lazyhat.compukterkraft.core.workbench.crdt.Op

/**
 * Outbound side of the CRDT sync protocol.
 *
 * - [sendOps] dispatches a batch of ops produced locally to the server. Called by [OpOutbox]
 *   on each flush.
 * - [sessionOpen] notifies the server that the editor is now actively viewing [path]; the
 *   server replies with a `WorkbenchDocumentSnapshotClientMessage` (handled separately and fed
 *   back into the store via [WorkbenchStore.onSnapshot]).
 * - [sendCursor] reports a caret position to the server so other collaborators viewing the
 *   same document can render it. `cursor == null` clears the local caret (e.g. on file close).
 *
 * Replaces the legacy `WorkspaceGateway.write` / `ComputerControlGateway.pullFromTarget` /
 * `pushToTarget` save/sync trio.
 */
interface WorkbenchOpsGateway {
    fun sendOps(
        path: String,
        ops: List<Op>,
    )

    fun sessionOpen(path: String)

    fun sendCursor(
        path: String,
        cursor: CursorAnchor?,
    )
}

/**
 * No-op default used by tests/contexts that do not care about op dispatch (e.g. unit tests
 * that exercise non-text behaviour or the legacy text-only path).
 */
object NoOpWorkbenchOpsGateway : WorkbenchOpsGateway {
    override fun sendOps(
        path: String,
        ops: List<Op>,
    ) {
    }

    override fun sessionOpen(path: String) {
    }

    override fun sendCursor(
        path: String,
        cursor: CursorAnchor?,
    ) {
    }
}
