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

import kotlin.test.Test
import kotlin.test.assertEquals

class CrdtDocumentTest {
    private val siteA = SiteId("p:test0001")
    private val siteB = SiteId("p:test0002") // siteB > siteA lexicographically
    private val siteC = SiteId("p:test0003")

    @Test
    fun emptyDocumentFlattensToEmptyString() {
        assertEquals("", CrdtDocument.empty().flatten())
    }

    @Test
    fun insertAtStartProducesText() {
        val op = Op.Insert(siteA, clock = 0, leftId = null, text = "hi")
        assertEquals("hi", CrdtDocument.empty().apply(op).flatten())
    }

    @Test
    fun fromTextRoundtrips() {
        assertEquals("hello world", CrdtDocument.fromText("hello world", SiteId.ServerInit).flatten())
    }

    @Test
    fun insertInMiddleSplitsRun() {
        // "abcd" by siteA → single run id=(siteA,0). Insert "X" with leftId=(siteA,1) lands after 'b'.
        val doc = CrdtDocument.fromText("abcd", siteA)
        val op = Op.Insert(siteB, clock = 0, leftId = AtomId(siteA, 1), text = "X")
        assertEquals("abXcd", doc.apply(op).flatten())
    }

    @Test
    fun deleteWholeRunMarksTombstoned() {
        val doc = CrdtDocument.fromText("hello", siteA)
        val del = Op.Delete(siteB, clock = 0, targetId = AtomId(siteA, 0), length = 5)
        val result = doc.apply(del)
        assertEquals("", result.flatten())
        // Tombstoned run still occupies the runs list to preserve causality.
        assertEquals(1, result.runs.size)
        assertEquals(true, result.runs[0].deleted)
    }

    @Test
    fun deletePartialMidRunSplits() {
        // "hello": delete "ell" (offset 1, length 3) → "ho".
        val doc = CrdtDocument.fromText("hello", siteA)
        val del = Op.Delete(siteB, clock = 0, targetId = AtomId(siteA, 1), length = 3)
        assertEquals("ho", doc.apply(del).flatten())
    }

    @Test
    fun deleteSpanningRunsTombstonesAll() {
        // Doc = "ab" (siteA) + "CD" inserted by siteB after 'b' = "abCD".
        var doc = CrdtDocument.fromText("ab", siteA)
        doc = doc.apply(Op.Insert(siteB, clock = 0, leftId = AtomId(siteA, 1), text = "CD"))
        assertEquals("abCD", doc.flatten())
        // Delete "bCD" — starts at (siteA,1), length 3, crosses into the siteB run.
        val del = Op.Delete(siteC, clock = 0, targetId = AtomId(siteA, 1), length = 3)
        assertEquals("a", doc.apply(del).flatten())
    }

    @Test
    fun applyTwiceIsIdempotent() {
        val op = Op.Insert(siteA, clock = 0, leftId = null, text = "hi")
        val once = CrdtDocument.empty().apply(op)
        val twice = once.apply(op)
        assertEquals(once.flatten(), twice.flatten())
        assertEquals(once.runs, twice.runs)
    }

    @Test
    fun tieBreakLargerSiteIsCloserToLeftIdRegardlessOfOrder() {
        // Two concurrent inserts at document start (leftId=null). siteB > siteA, so siteB's run
        // is placed CLOSER to the anchor (start) ⇒ result is "BA" no matter which arrives first.
        val opA = Op.Insert(siteA, clock = 0, leftId = null, text = "A")
        val opB = Op.Insert(siteB, clock = 0, leftId = null, text = "B")

        val docAB = CrdtDocument.empty().apply(opA).apply(opB)
        val docBA = CrdtDocument.empty().apply(opB).apply(opA)

        assertEquals("BA", docAB.flatten())
        assertEquals(docAB.flatten(), docBA.flatten())
    }
}
