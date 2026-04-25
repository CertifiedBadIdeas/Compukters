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
 * A unit of replication.
 *
 * Clock convention: an [Insert] of `text.length = N` characters occupies the [AtomId]s
 * `(author, clock), (author, clock + 1), ..., (author, clock + N - 1)`. The author advances
 * its `nextClock` by `N` per insert. This keeps every character addressable so that splits and
 * cross-run deletes always have stable references.
 */
sealed interface Op {
    val author: SiteId

    /** Starting clock occupied by this op. For [Insert] the op spans `[clock, clock + text.length - 1]`. */
    val clock: Int

    /** The first [AtomId] this op produced or targeted. */
    val firstAtomId: AtomId get() = AtomId(author, clock)

    /**
     * Insert [text] immediately to the right of [leftId] (or at document start when [leftId] is null).
     * Concurrent inserts sharing [leftId] are ordered by descending `(author, clock)` — the larger
     * pair is placed closer to [leftId].
     */
    data class Insert(
        override val author: SiteId,
        override val clock: Int,
        val leftId: AtomId?,
        val text: String,
    ) : Op {
        init {
            require(clock >= 0) { "clock must be non-negative" }
            require(text.isNotEmpty()) { "Insert.text must be non-empty" }
        }
    }

    /**
     * Delete [length] visible characters starting at the position of [targetId]. Tombstones the
     * touched runs (splitting at the boundary if necessary). Ops that reach past the end of the
     * document are clamped to whatever visible characters exist and ignore the rest — this
     * keeps deletes idempotent across replicas with mismatched op-arrival order.
     */
    data class Delete(
        override val author: SiteId,
        override val clock: Int,
        val targetId: AtomId,
        val length: Int,
    ) : Op {
        init {
            require(clock >= 0) { "clock must be non-negative" }
            require(length > 0) { "Delete.length must be positive, got $length" }
        }
    }
}
