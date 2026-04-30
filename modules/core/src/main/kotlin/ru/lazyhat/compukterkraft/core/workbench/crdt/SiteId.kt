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

import java.util.UUID

/**
 * Identifier of a CRDT replica that produced an op.
 *
 * Compact string form so it serializes cheaply over the wire:
 * - `"s:i"` — server-init replica that atomizes the file when first loaded from disk.
 * - `"p:<8charUuid>"` — a player-editor session.
 * - `"t:<computerId>"` — a target computer (Phase 2; reserved).
 *
 * Length is capped at 32 chars; this is more than enough for any of the formats above and keeps
 * the wire encoding bounded.
 */
@JvmInline
value class SiteId(
    val raw: String,
) : Comparable<SiteId> {
    init {
        require(raw.isNotEmpty() && raw.length <= MAX_LENGTH) {
            "SiteId must be 1..$MAX_LENGTH chars, got ${raw.length}: '$raw'"
        }
    }

    override fun compareTo(other: SiteId): Int = raw.compareTo(other.raw)

    companion object {
        const val MAX_LENGTH: Int = 32

        /**
         * Bootstrap replica used to atomize plain text on file open. Its raw form starts with
         * `'!'` (ASCII 33) so it lexicographically sorts BEFORE any `p:` (player) or `t:`
         * (target) site, which means server-init atoms always LOSE the RGA tie-break against
         * real authors. Concretely: when a player inserts at offset 0 of a freshly atomized
         * file, their new run is placed before the bootstrap content rather than after it.
         */
        val ServerInit: SiteId = SiteId("!:i")

        fun player(uuid: UUID): SiteId = SiteId("p:" + uuid.toString().replace("-", "").take(8))

        fun target(computerId: Int): SiteId = SiteId("t:$computerId")
    }
}
