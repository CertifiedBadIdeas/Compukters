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

class ClientCrdtReplicaTest {
    private val site = SiteId("p:test0001")
    private val server = SiteId.ServerInit

    @Test
    fun produceInsertAtStartOfEmptyDocument() {
        val replica = ClientCrdtReplica(site, CrdtDocument.empty())
        val op = replica.produceInsert(charOffset = 0, text = "hi")
        assertEquals(site, op.author)
        assertEquals(0, op.clock)
        assertEquals(null, op.leftId)
        assertEquals("hi", op.text)
    }

    @Test
    fun produceInsertAfterApplyIncrementsClockByTextLength() {
        val replica = ClientCrdtReplica(site, CrdtDocument.empty())
        val first = replica.produceInsert(0, "hi")
        replica.applyLocal(first)
        val second = replica.produceInsert(charOffset = 2, text = "!")
        assertEquals(2, second.clock) // hi consumed clocks 0,1
        assertEquals(AtomId(site, 1), second.leftId) // anchored to last char of "hi"
    }

    @Test
    fun produceInsertInMiddleAnchorsToLeftCharacter() {
        // Doc starts with "hello" by server-init.
        val replica = ClientCrdtReplica(site, CrdtDocument.fromText("hello", server))
        val op = replica.produceInsert(charOffset = 2, text = "X")
        // leftId should be the atom of 'e' (the char before insertion offset).
        assertEquals(AtomId(server, 1), op.leftId)
    }

    @Test
    fun produceDeleteTargetsAtomAtOffset() {
        val replica = ClientCrdtReplica(site, CrdtDocument.fromText("hello", server))
        val op = replica.produceDelete(charOffset = 1, length = 3) // "ell"
        assertEquals(AtomId(server, 1), op.targetId)
        assertEquals(3, op.length)
    }

    @Test
    fun applyLocalUpdatesDocument() {
        val replica = ClientCrdtReplica(site, CrdtDocument.empty())
        val op = replica.produceInsert(0, "hi")
        replica.applyLocal(op)
        assertEquals("hi", replica.document.flatten())
    }

    @Test
    fun applyRemoteUpdatesDocumentWithoutAdvancingNextClock() {
        val replica = ClientCrdtReplica(site, CrdtDocument.empty())
        val before = replica.nextClock
        replica.applyRemote(Op.Insert(server, clock = 0, leftId = null, text = "remote"))
        assertEquals("remote", replica.document.flatten())
        assertEquals(before, replica.nextClock)
    }

    @Test
    fun applyAckUpdatesLastAckedClock() {
        val replica = ClientCrdtReplica(site, CrdtDocument.empty())
        val op = replica.produceInsert(0, "hi")
        replica.applyLocal(op)
        assertEquals(-1, replica.lastAckedClock)
        replica.applyAck(1) // last clock consumed by "hi" is 1
        assertEquals(1, replica.lastAckedClock)
    }

    @Test
    fun applyAckIgnoresStaleAcks() {
        val replica = ClientCrdtReplica(site, CrdtDocument.empty())
        replica.applyLocal(replica.produceInsert(0, "hello")) // clocks 0..4
        replica.applyAck(4)
        replica.applyAck(2) // older — must not regress
        assertEquals(4, replica.lastAckedClock)
    }

    @Test
    fun cursorAtOffsetAtStartReturnsFirstAtom() {
        val replica = ClientCrdtReplica(site, CrdtDocument.fromText("abc", server))
        val cursor = replica.cursorAtOffset(0)
        assertEquals(AtomId(server, 0), cursor.atomId)
        assertEquals(0, cursor.offsetWithinRun)
    }

    @Test
    fun cursorAtOffsetAtEndAnchorsAtLastVisibleAtom() {
        val replica = ClientCrdtReplica(site, CrdtDocument.fromText("abc", server))
        val cursor = replica.cursorAtOffset(3)
        // End-of-document cursor anchors at the last char's atom with offset = 1 past it.
        assertEquals(AtomId(server, 2), cursor.atomId)
        assertEquals(1, cursor.offsetWithinRun)
    }

    @Test
    fun relocateCursorSnapsToRightNeighbourIfTargetIsTombstoned() {
        // Doc: "abc". Cursor at (server, 1) ('b'). Tombstone the run.
        var replica = ClientCrdtReplica(site, CrdtDocument.fromText("abc", server))
        val cursor = CursorAnchor(AtomId(server, 1), 0)
        // Apply a delete that tombstones the entire run.
        replica.applyRemote(Op.Delete(server, clock = 5, targetId = AtomId(server, 0), length = 3))
        val relocated = replica.relocateCursor(cursor)
        // Whole document is gone — cursor must collapse to start (null atom).
        assertEquals(null, relocated.atomId)
        assertEquals(0, relocated.offsetWithinRun)
    }

    @Test
    fun produceInsertAtBeginningOfNonEmptyDoc() {
        val replica = ClientCrdtReplica(site, CrdtDocument.fromText("hello", server))
        val op = replica.produceInsert(charOffset = 0, text = "X")
        assertEquals(null, op.leftId)
        replica.applyLocal(op)
        assertEquals("Xhello", replica.document.flatten())
    }

    @Test
    fun multipleProducedInsertsConverge() {
        val replica = ClientCrdtReplica(site, CrdtDocument.empty())
        replica.applyLocal(replica.produceInsert(0, "h"))
        replica.applyLocal(replica.produceInsert(1, "i"))
        replica.applyLocal(replica.produceInsert(0, "X"))
        assertTrue(replica.document.flatten() == "Xhi")
    }
}
