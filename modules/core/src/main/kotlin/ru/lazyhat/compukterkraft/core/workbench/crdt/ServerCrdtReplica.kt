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
 * Result of [ServerCrdtReplica.apply].
 *
 * [applied] preserves order of acceptance; [rejected] holds ops the replica refused because
 * their causal predecessors (`leftId` / `targetId`) were unknown — the caller is expected to
 * re-snapshot the offending sender. [ackedClockBySite] reports the highest applied clock per
 * author across [applied] only.
 */
data class CrdtApplyResult(
    val applied: List<Op>,
    val rejected: List<Op>,
    val ackedClockBySite: Map<SiteId, Int>,
)

/**
 * Server-side CRDT replica. Validates op causality before applying and tracks the highest
 * clock applied per author so the network layer can produce ack messages.
 *
 * Not thread-safe — concurrency is contained at the [ServerWorkbench] level via per-session
 * synchronization.
 */
class ServerCrdtReplica(
    initial: CrdtDocument,
) {
    var document: CrdtDocument = initial
        private set

    fun apply(ops: List<Op>): CrdtApplyResult {
        val applied = ArrayList<Op>(ops.size)
        val rejected = ArrayList<Op>()
        val ackedClockBySite = HashMap<SiteId, Int>()
        for (op in ops) {
            if (!isCausallyValid(op)) {
                rejected.add(op)
                continue
            }
            document = document.apply(op)
            applied.add(op)
            val highest =
                when (op) {
                    is Op.Insert -> op.clock + op.text.length - 1
                    is Op.Delete -> op.clock
                }
            ackedClockBySite.merge(op.author, highest) { a, b -> maxOf(a, b) }
        }
        return CrdtApplyResult(applied, rejected, ackedClockBySite)
    }

    private fun isCausallyValid(op: Op): Boolean =
        when (op) {
            is Op.Insert -> op.leftId == null || document.containsAtom(op.leftId)
            is Op.Delete -> document.containsAtom(op.targetId)
        }

    fun flatten(): String = document.flatten()

    fun versionVector(): Map<SiteId, Int> = document.clockBySite
}
