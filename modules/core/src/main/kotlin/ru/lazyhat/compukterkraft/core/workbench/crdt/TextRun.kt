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

package ru.lazyhat.compukterkraft.core.workbench.crdt

/**
 * A consecutive block of characters authored as a single atomic unit.
 *
 * Splits when a remote op inserts or deletes inside it. Once split, the resulting halves keep
 * their original [id] / [leftId] linkage from the parent run: the left half keeps the parent's
 * [id], the right half gets a fresh derived id whose [AtomId.site] equals the parent's site and
 * [AtomId.clock] is offset within the original run, so descendant references remain valid.
 *
 * `deleted = true` marks a tombstone — the run still occupies its position in the runs list to
 * preserve causality for ops that reference it, but contributes no characters to [CrdtDocument
 * .flatten].
 */
data class TextRun(
    val id: AtomId,
    val leftId: AtomId?,
    val text: String,
    val deleted: Boolean = false,
) {
    init {
        require(text.isNotEmpty()) { "TextRun.text must be non-empty (use deleted=true to tombstone)" }
    }

    /** Effective number of characters this run contributes when flattened. */
    val visibleLength: Int get() = if (deleted) 0 else text.length
}
