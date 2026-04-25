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
 * Globally-unique identifier of a CRDT atom (a [TextRun] or a logical position).
 *
 * Total ordering is by `(site, clock)` lex order; this is also the tie-break used when two
 * inserts share the same `leftId`. The site coming later in the order wins (its run is placed
 * closer to `leftId`).
 */
data class AtomId(
    val site: SiteId,
    val clock: Int,
) : Comparable<AtomId> {
    init {
        require(clock >= 0) { "clock must be non-negative, got $clock" }
    }

    override fun compareTo(other: AtomId): Int {
        val c = site.compareTo(other.site)
        return if (c != 0) c else clock.compareTo(other.clock)
    }
}
