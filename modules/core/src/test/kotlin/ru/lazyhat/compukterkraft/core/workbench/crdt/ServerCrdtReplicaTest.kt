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
import kotlin.test.assertTrue

class ServerCrdtReplicaTest {
    private val server = SiteId.ServerInit
    private val playerA = SiteId("p:aaaaaaaa")
    private val playerB = SiteId("p:bbbbbbbb")

    @Test
    fun applyKnownOpsSucceeds() {
        val replica = ServerCrdtReplica(CrdtDocument.fromText("hello", server))
        val op = Op.Insert(playerA, clock = 0, leftId = AtomId(server, 4), text = "!")
        val result = replica.apply(listOf(op))
        assertEquals(listOf(op), result.applied)
        assertTrue(result.rejected.isEmpty())
        assertEquals(0, result.ackedClockBySite[playerA])
        assertEquals("hello!", replica.flatten())
    }

    @Test
    fun applyMultipleOpsAdvancesAcksToHighestClock() {
        val replica = ServerCrdtReplica(CrdtDocument.empty())
        val ops =
            listOf(
                Op.Insert(playerA, clock = 0, leftId = null, text = "ab"), // clocks 0,1
                Op.Insert(playerA, clock = 2, leftId = AtomId(playerA, 1), text = "c"), // clock 2
            )
        val result = replica.apply(ops)
        assertEquals(2, result.applied.size)
        assertEquals(2, result.ackedClockBySite[playerA])
        assertEquals("abc", replica.flatten())
    }

    @Test
    fun applyRejectsOpWithUnknownLeftId() {
        val replica = ServerCrdtReplica(CrdtDocument.fromText("hi", server))
        val unknown = AtomId(playerB, 999)
        val op = Op.Insert(playerA, clock = 0, leftId = unknown, text = "?")
        val result = replica.apply(listOf(op))
        assertTrue(result.applied.isEmpty())
        assertEquals(listOf(op), result.rejected)
        assertEquals("hi", replica.flatten()) // doc unchanged
    }

    @Test
    fun applyRejectsOpWithUnknownDeleteTarget() {
        val replica = ServerCrdtReplica(CrdtDocument.fromText("hi", server))
        val op = Op.Delete(playerA, clock = 0, targetId = AtomId(playerB, 999), length = 1)
        val result = replica.apply(listOf(op))
        assertTrue(result.applied.isEmpty())
        assertEquals(listOf(op), result.rejected)
    }

    @Test
    fun applyAcceptsValidOpsEvenWhenLaterOnesAreRejected() {
        val replica = ServerCrdtReplica(CrdtDocument.fromText("hi", server))
        val good = Op.Insert(playerA, clock = 0, leftId = AtomId(server, 1), text = "!")
        val bad = Op.Insert(playerA, clock = 1, leftId = AtomId(playerB, 999), text = "?")
        val result = replica.apply(listOf(good, bad))
        assertEquals(listOf(good), result.applied)
        assertEquals(listOf(bad), result.rejected)
    }

    @Test
    fun versionVectorReflectsHighestClockPerSite() {
        val replica = ServerCrdtReplica(CrdtDocument.empty())
        replica.apply(listOf(Op.Insert(playerA, 0, null, "abc"))) // clocks 0..2
        replica.apply(listOf(Op.Insert(playerB, 0, null, "X"))) // clock 0
        val vv = replica.versionVector()
        assertEquals(2, vv[playerA])
        assertEquals(0, vv[playerB])
    }
}
