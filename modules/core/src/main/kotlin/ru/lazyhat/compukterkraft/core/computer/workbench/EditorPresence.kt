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
package ru.lazyhat.compukterkraft.core.computer.workbench

import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.CursorAnchor
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId

/**
 * Live-collaboration presence: which player is editing which file inside a workbench, and where
 * their caret is sitting right now.
 *
 * Broadcast workbench-wide (all open menus on the same workbench see every presence) so the
 * file tree can render per-path editor counts. The optional [cursor] is meaningful only when
 * a peer is editing the same [path] as the local viewer — other paths are purely informational.
 *
 * `displayName` is the player's profile name at the moment of join; we don't try to keep it
 * updated if the name changes mid-session — Minecraft profile names rarely change and a stale
 * name is harmless.
 */
data class EditorPresence(
    val siteId: SiteId,
    val displayName: String,
    val path: String,
    val cursor: CursorAnchor? = null,
)
