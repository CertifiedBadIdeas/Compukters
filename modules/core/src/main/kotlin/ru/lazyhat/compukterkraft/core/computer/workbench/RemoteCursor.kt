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

/**
 * A remote collaborator's caret tracked client-side.
 *
 * [path] lets the editor decide whether to render this cursor on the currently open file or
 * skip it (caret on a different file is still useful for the file-tree presence count, but
 * not for the in-editor overlay). [cursor] is the live CRDT anchor — it survives concurrent
 * inserts/deletes by other authors because it points at a stable atom rather than a flat
 * offset.
 */
data class RemoteCursor(
    val path: String,
    val cursor: CursorAnchor,
)
