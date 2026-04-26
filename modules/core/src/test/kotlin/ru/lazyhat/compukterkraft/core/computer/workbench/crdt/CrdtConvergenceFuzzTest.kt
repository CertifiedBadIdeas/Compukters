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

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stresses the core CRDT convergence guarantee: two replicas that observe the same set of ops
 * produce the same [CrdtDocument.flatten] regardless of arrival order, as long as per-author
 * order is preserved (which is what the network ordering guarantees us in practice).
 */
class CrdtConvergenceFuzzTest {
    private val siteA = SiteId("p:aaaaaaaa")
    private val siteB = SiteId("p:bbbbbbbb") // > siteA lex

    @Test
    fun convergesUnderManyInterleavings() {
        val opsA = generateInsertsAtStart(siteA, count = 200, seed = 1L)
        val opsB = generateInsertsAtStart(siteB, count = 200, seed = 2L)

        val baseline = (opsA + opsB).fold(CrdtDocument.empty()) { d, op -> d.apply(op) }

        // Try several different interleavings; per-author order is preserved in each.
        repeat(10) { i ->
            val interleaved = interleavePreservingPerAuthorOrder(opsA, opsB, seed = 100L + i)
            val replica = interleaved.fold(CrdtDocument.empty()) { d, op -> d.apply(op) }
            assertEquals(
                baseline.flatten(),
                replica.flatten(),
                "diverged on interleaving $i",
            )
        }
    }

    @Test
    fun convergesWithDeletesInterleaved() {
        // Mix inserts and deletes per author. Deletes only target this author's own atoms (so the
        // op is causally valid the moment it's applied — same-author monotonic clocks guarantee
        // that the targeted run already exists).
        val opsA = generateInsertsAndDeletes(siteA, count = 200, seed = 11L)
        val opsB = generateInsertsAndDeletes(siteB, count = 200, seed = 22L)

        val baseline = (opsA + opsB).fold(CrdtDocument.empty()) { d, op -> d.apply(op) }

        repeat(5) { i ->
            val interleaved = interleavePreservingPerAuthorOrder(opsA, opsB, seed = 200L + i)
            val replica = interleaved.fold(CrdtDocument.empty()) { d, op -> d.apply(op) }
            assertEquals(
                baseline.flatten(),
                replica.flatten(),
                "diverged on insert+delete interleaving $i",
            )
        }
    }

    private fun generateInsertsAtStart(
        site: SiteId,
        count: Int,
        seed: Long,
    ): List<Op> {
        val random = Random(seed)
        var clock = 0
        return List(count) {
            val len = random.nextInt(1, 5)
            val text = (0 until len).map { ('a' + random.nextInt(26)) }.joinToString("")
            val op = Op.Insert(site, clock = clock, leftId = null, text = text)
            clock += len
            op
        }
    }

    /**
     * Generates a stream of Inserts (leftId=null) and Deletes (targeting the author's own
     * earliest-still-alive atom). Doesn't aim for realism — only that every op is causally
     * valid at the moment it's applied in the author's own stream.
     */
    private fun generateInsertsAndDeletes(
        site: SiteId,
        count: Int,
        seed: Long,
    ): List<Op> {
        val random = Random(seed)
        val ops = mutableListOf<Op>()
        var clock = 0
        // Track the ranges (start clock, length) of inserts the author has produced.
        val ownInsertSpans = mutableListOf<Pair<Int, Int>>()
        repeat(count) {
            val isDelete = ops.isNotEmpty() && random.nextDouble() < 0.3 && ownInsertSpans.isNotEmpty()
            if (isDelete) {
                val (spanClock, spanLen) = ownInsertSpans.removeAt(random.nextInt(ownInsertSpans.size))
                ops.add(Op.Delete(site, clock = clock, targetId = AtomId(site, spanClock), length = spanLen))
                clock += 1
            } else {
                val len = random.nextInt(1, 5)
                val text = (0 until len).map { ('a' + random.nextInt(26)) }.joinToString("")
                ops.add(Op.Insert(site, clock = clock, leftId = null, text = text))
                ownInsertSpans.add(clock to len)
                clock += len
            }
        }
        return ops
    }

    private fun interleavePreservingPerAuthorOrder(
        opsA: List<Op>,
        opsB: List<Op>,
        seed: Long,
    ): List<Op> {
        val random = Random(seed)
        val result = ArrayList<Op>(opsA.size + opsB.size)
        var ia = 0
        var ib = 0
        while (ia < opsA.size && ib < opsB.size) {
            if (random.nextBoolean()) result.add(opsA[ia++]) else result.add(opsB[ib++])
        }
        while (ia < opsA.size) result.add(opsA[ia++])
        while (ib < opsB.size) result.add(opsB[ib++])
        return result
    }
}
