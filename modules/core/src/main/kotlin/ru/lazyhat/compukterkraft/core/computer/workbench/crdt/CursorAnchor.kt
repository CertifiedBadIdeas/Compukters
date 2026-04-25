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

package ru.lazyhat.compukterkraft.core.computer.workbench.crdt

/**
 * Position of an editor cursor within a [CrdtDocument].
 *
 * Convention:
 * - `atomId == null` means the cursor sits at document start (before any visible run).
 * - `offsetWithinRun == 0` means the cursor is positioned BEFORE the addressed atom.
 * - `offsetWithinRun == 1` means the cursor is positioned AFTER the addressed atom (used at
 *   document end so the cursor still has a stable anchor when the doc is non-empty).
 *
 * Anchoring on an [AtomId] (instead of a flat character offset) is what makes the cursor
 * survive remote inserts/deletes — when other replicas mutate the runs list, the cursor stays
 * pinned to the same logical character even though its visible position shifts.
 */
data class CursorAnchor(
    val atomId: AtomId?,
    val offsetWithinRun: Int,
)
