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

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * Pure-functional CRDT document built on RGA-with-runs (Replicated Growable Array).
 *
 * The runs list is kept in materialization order (left-to-right). [flatten] concatenates the
 * `text` of every non-tombstoned [TextRun] in that order. Two replicas that have observed the
 * same set of [Op]s produce identical [runs] (and therefore identical [flatten]) regardless of
 * arrival order — see `CrdtConvergenceFuzzTest`.
 *
 * Apply is O(N) over [runs] today: [findRunContaining] does a linear scan. The plan calls out a
 * `Map<AtomId, Int>` index as a future optimization once profiling shows it pays off.
 */
data class CrdtDocument(
    val runs: PersistentList<TextRun>,
    /** Highest [AtomId.clock] observed per author. Doubles as the version vector. */
    val clockBySite: PersistentMap<SiteId, Int>,
) {
    /** Concatenates non-tombstoned runs in order. */
    fun flatten(): String = buildString {
        for (run in runs) if (!run.deleted) append(run.text)
    }

    /**
     * Apply [op] producing a new document. No-op if [op] is a duplicate (its clock falls at or
     * below the highest clock already observed for [Op.author]).
     */
    fun apply(op: Op): CrdtDocument {
        val lastSeen = clockBySite[op.author] ?: -1
        if (op.clock <= lastSeen) return this
        return when (op) {
            is Op.Insert -> applyInsert(op)
            is Op.Delete -> applyDelete(op)
        }
    }

    private fun applyInsert(op: Op.Insert): CrdtDocument {
        val newRun = TextRun(id = op.firstAtomId, leftId = op.leftId, text = op.text)
        val (splitRuns, anchorIndex) = splitAfterAnchor(runs, op.leftId)
        val insertAt = findInsertPosition(splitRuns, anchorIndex, op.leftId, newRun.id)
        val newRuns = splitRuns.add(insertAt, newRun)
        val advancedClock = op.clock + op.text.length - 1
        val maxClock = maxOf(clockBySite[op.author] ?: -1, advancedClock)
        return copy(runs = newRuns, clockBySite = clockBySite.put(op.author, maxClock))
    }

    private fun applyDelete(op: Op.Delete): CrdtDocument {
        var working = runs
        val startIndex: Int
        val (splitRuns, idx) = splitBeforeTarget(working, op.targetId)
        working = splitRuns
        startIndex = idx

        var remaining = op.length
        var i = startIndex
        while (remaining > 0 && i < working.size) {
            val r = working[i]
            if (r.deleted) {
                i++
                continue
            }
            if (r.text.length <= remaining) {
                working = working.set(i, r.copy(deleted = true))
                remaining -= r.text.length
                i++
            } else {
                // Tombstone the first `remaining` characters of this run, keep the tail alive.
                val leftHalf = TextRun(
                    id = r.id,
                    leftId = r.leftId,
                    text = r.text.substring(0, remaining),
                    deleted = true,
                )
                val rightHalf = TextRun(
                    id = AtomId(r.id.site, r.id.clock + remaining),
                    leftId = AtomId(r.id.site, r.id.clock + remaining - 1),
                    text = r.text.substring(remaining),
                    deleted = false,
                )
                working = working.set(i, leftHalf).add(i + 1, rightHalf)
                remaining = 0
            }
        }

        val maxClock = maxOf(clockBySite[op.author] ?: -1, op.clock)
        return copy(runs = working, clockBySite = clockBySite.put(op.author, maxClock))
    }

    /**
     * Walk past concurrent inserts that share [leftId] but tie-break stronger than [newRunId]
     * (i.e. their id is greater — they are placed closer to the anchor). Stop at the first run
     * that either has a different leftId or whose id is smaller than [newRunId].
     */
    private fun findInsertPosition(
        runs: PersistentList<TextRun>,
        anchorIndex: Int,
        leftId: AtomId?,
        newRunId: AtomId,
    ): Int {
        var i = anchorIndex + 1
        while (i < runs.size) {
            val r = runs[i]
            if (r.leftId != leftId) break
            if (r.id < newRunId) break
            i++
        }
        return i
    }

    /**
     * Ensures the run boundary lies AT [leftId]: if [leftId] points to the middle of a run,
     * splits that run so the left half ends exactly at [leftId]. Returns the modified runs list
     * and the index of the run whose last atom is [leftId] (or `-1` if [leftId] is null).
     */
    private fun splitAfterAnchor(
        runs: PersistentList<TextRun>,
        leftId: AtomId?,
    ): Pair<PersistentList<TextRun>, Int> {
        if (leftId == null) return runs to -1
        val containingIndex = findRunContaining(runs, leftId)
            ?: error("Insert references unknown leftId=$leftId; replica state diverged")
        val r = runs[containingIndex]
        val baseClock = r.id.clock
        val offset = leftId.clock - baseClock
        if (offset == r.text.length - 1) return runs to containingIndex

        val leftHalf = r.copy(text = r.text.substring(0, offset + 1))
        val rightHalf = TextRun(
            id = AtomId(r.id.site, baseClock + offset + 1),
            leftId = AtomId(r.id.site, baseClock + offset),
            text = r.text.substring(offset + 1),
            deleted = r.deleted,
        )
        return runs.set(containingIndex, leftHalf).add(containingIndex + 1, rightHalf) to containingIndex
    }

    /**
     * Ensures the run boundary lies AT [targetId]: if [targetId] points into the middle of a
     * run, splits so the right half BEGINS at [targetId]. Returns the modified runs list and
     * the index of the right half (the run that starts with [targetId]).
     */
    private fun splitBeforeTarget(
        runs: PersistentList<TextRun>,
        targetId: AtomId,
    ): Pair<PersistentList<TextRun>, Int> {
        val containingIndex = findRunContaining(runs, targetId)
            ?: error("Delete references unknown targetId=$targetId; replica state diverged")
        val r = runs[containingIndex]
        val baseClock = r.id.clock
        val offset = targetId.clock - baseClock
        if (offset == 0) return runs to containingIndex

        val leftHalf = r.copy(text = r.text.substring(0, offset))
        val rightHalf = TextRun(
            id = AtomId(r.id.site, baseClock + offset),
            leftId = AtomId(r.id.site, baseClock + offset - 1),
            text = r.text.substring(offset),
            deleted = r.deleted,
        )
        return runs.set(containingIndex, leftHalf).add(containingIndex + 1, rightHalf) to (containingIndex + 1)
    }

    private fun findRunContaining(runs: PersistentList<TextRun>, atomId: AtomId): Int? {
        for (i in runs.indices) {
            val r = runs[i]
            if (r.id.site != atomId.site) continue
            val baseClock = r.id.clock
            val endExclusive = baseClock + r.text.length
            if (atomId.clock in baseClock until endExclusive) return i
        }
        return null
    }

    companion object {
        fun empty(): CrdtDocument = CrdtDocument(persistentListOf(), persistentMapOf())

        /** Atomize plain text into a single run authored by [site]. Empty text → empty document. */
        fun fromText(text: String, site: SiteId): CrdtDocument {
            if (text.isEmpty()) return empty()
            return empty().apply(Op.Insert(site, clock = 0, leftId = null, text = text))
        }
    }
}
