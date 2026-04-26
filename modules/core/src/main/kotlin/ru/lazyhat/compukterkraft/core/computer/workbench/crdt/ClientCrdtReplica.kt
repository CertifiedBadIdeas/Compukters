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
 * Client-side CRDT replica with a private clock counter for [siteId].
 *
 * Wraps a mutable [document] reference: every produced/applied op updates [document]
 * functionally and replaces the field. Owns the next-clock counter for [siteId] and tracks
 * [lastAckedClock] so the [WorkbenchStore]'s `flushAndRun` knows when it's safe to RUN.
 *
 * Not thread-safe — must be confined to the editor's coroutine context.
 */
class ClientCrdtReplica(
    val siteId: SiteId,
    initial: CrdtDocument,
) {
    var document: CrdtDocument = initial
        private set

    /** Clock to assign to the next op produced by this replica. */
    var nextClock: Int = (initial.clockBySite[siteId] ?: -1) + 1
        private set

    /** Highest clock from this replica that the server has confirmed applied. */
    var lastAckedClock: Int = initial.clockBySite[siteId] ?: -1
        private set

    /**
     * Build an [Op.Insert] for inserting [text] at [charOffset] in the visible document. Does
     * NOT apply the op — call [applyLocal] separately.
     */
    fun produceInsert(
        charOffset: Int,
        text: String,
    ): Op.Insert {
        val leftId =
            if (charOffset == 0) {
                null
            } else {
                document.atomAtOffset(charOffset - 1)?.first
                    ?: error("produceInsert: offset $charOffset out of bounds (visible length ${document.visibleLength})")
            }
        return Op.Insert(siteId, nextClock, leftId, text)
    }

    /** Build an [Op.Delete] removing [length] characters starting at [charOffset]. */
    fun produceDelete(
        charOffset: Int,
        length: Int,
    ): Op.Delete {
        val target =
            document.atomAtOffset(charOffset)?.first
                ?: error("produceDelete: offset $charOffset out of bounds (visible length ${document.visibleLength})")
        return Op.Delete(siteId, nextClock, target, length)
    }

    /** Apply a locally-produced op: updates [document] and advances [nextClock]. */
    fun applyLocal(op: Op) {
        require(op.author == siteId) { "applyLocal: op author ${op.author} != replica site $siteId" }
        document = document.apply(op)
        nextClock =
            when (op) {
                is Op.Insert -> op.clock + op.text.length
                is Op.Delete -> op.clock + 1
            }
    }

    /** Apply a remote op: updates [document] only, [nextClock] is untouched. */
    fun applyRemote(op: Op) {
        require(op.author != siteId) { "applyRemote: op author == replica site $siteId; use applyLocal" }
        document = document.apply(op)
    }

    /** Update [lastAckedClock] monotonically; stale acks are ignored. */
    fun applyAck(clock: Int) {
        if (clock > lastAckedClock) lastAckedClock = clock
    }

    /** Translate a flat visible offset to a [CursorAnchor] that survives remote mutations. */
    fun cursorAtOffset(offset: Int): CursorAnchor {
        val pair = document.atomAtOffset(offset) ?: return CursorAnchor(null, 0)
        return CursorAnchor(pair.first, pair.second)
    }

    /**
     * If [cursor]'s anchor was tombstoned by a remote delete, snap to the next visible run on
     * its right. Returns the original anchor unchanged when the run is still visible. Falls
     * back to a `(null, 0)` start-of-doc anchor if nothing visible remains to the right.
     */
    fun relocateCursor(cursor: CursorAnchor): CursorAnchor {
        if (cursor.atomId == null) return cursor
        for (i in document.runs.indices) {
            val r = document.runs[i]
            if (r.id.site != cursor.atomId.site) continue
            val end = r.id.clock + r.text.length
            if (cursor.atomId.clock !in r.id.clock until end) continue
            if (!r.deleted) return cursor
            // Tombstoned — find next visible run.
            for (j in i + 1 until document.runs.size) {
                val next = document.runs[j]
                if (!next.deleted) return CursorAnchor(next.id, 0)
            }
            return CursorAnchor(null, 0)
        }
        // Anchor's atom is unknown to us; collapse to start.
        return CursorAnchor(null, 0)
    }
}
