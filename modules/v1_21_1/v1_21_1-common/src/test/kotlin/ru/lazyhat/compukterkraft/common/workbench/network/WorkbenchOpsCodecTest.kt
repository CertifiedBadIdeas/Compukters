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

package ru.lazyhat.compukterkraft.common.workbench.network

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchDocumentSnapshotClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchOpsClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchOpsServerMessage
import ru.lazyhat.compukterkraft.common.workbench.test.TestMinecraftBootstrap
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.AtomId
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.TextRun
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkbenchOpsCodecTest {

    @BeforeTest
    fun setUp() {
        TestMinecraftBootstrap.ensureInitialized()
    }

    private fun freshBuf(): FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer())

    private val playerSite = SiteId.player(java.util.UUID(1L, 2L))

    @Test
    fun opsServerMessageRoundTripCarriesInsertAndDelete() {
        val msg = WorkbenchOpsServerMessage(
            containerId = 7,
            path = "main.lua",
            ops = listOf(
                Op.Insert(playerSite, clock = 1, leftId = null, text = "hello"),
                Op.Insert(
                    playerSite,
                    clock = 6,
                    leftId = AtomId(SiteId.ServerInit, 0),
                    text = "X",
                ),
                Op.Delete(
                    playerSite,
                    clock = 7,
                    targetId = AtomId(SiteId.ServerInit, 1),
                    length = 3,
                ),
            ),
        )
        val buf = freshBuf()
        msg.write(buf)

        val restored = WorkbenchOpsServerMessage(buf)
        assertEquals(msg, restored)
        assertEquals(0, buf.readableBytes(), "buffer must be fully consumed")
    }

    @Test
    fun opsClientMessageRoundTripCarriesAck() {
        val msg = WorkbenchOpsClientMessage(
            containerId = 42,
            path = "lib/util.lua",
            ops = listOf(
                Op.Insert(SiteId.player(java.util.UUID(9L, 9L)), 0, null, "world"),
            ),
            ackedClock = 12,
        )
        val buf = freshBuf()
        msg.write(buf)

        val restored = WorkbenchOpsClientMessage(buf)
        assertEquals(msg, restored)
        assertEquals(0, buf.readableBytes())
    }

    @Test
    fun opsClientMessageHandlesEmptyOpsList() {
        val msg = WorkbenchOpsClientMessage(
            containerId = 1,
            path = "x",
            ops = emptyList(),
            ackedClock = 0,
        )
        val buf = freshBuf()
        msg.write(buf)

        val restored = WorkbenchOpsClientMessage(buf)
        assertEquals(msg, restored)
    }

    @Test
    fun snapshotMessageRoundTripWithMixedAuthorsAndTombstones() {
        val a = SiteId.ServerInit
        val b = playerSite
        val msg = WorkbenchDocumentSnapshotClientMessage(
            containerId = 3,
            path = "doc.lua",
            runs = listOf(
                TextRun(id = AtomId(a, 0), leftId = null, text = "abc", deleted = false),
                TextRun(id = AtomId(b, 4), leftId = AtomId(a, 2), text = "X", deleted = true),
                TextRun(id = AtomId(a, 3), leftId = AtomId(a, 2), text = "de", deleted = false),
            ),
            versionVector = mapOf(a to 5, b to 4),
        )
        val buf = freshBuf()
        msg.write(buf)

        val restored = WorkbenchDocumentSnapshotClientMessage(buf)
        assertEquals(msg, restored)
        assertEquals(0, buf.readableBytes())
    }

    @Test
    fun snapshotMessageRoundTripEmpty() {
        val msg = WorkbenchDocumentSnapshotClientMessage(
            containerId = 0,
            path = "",
            runs = emptyList(),
            versionVector = emptyMap(),
        )
        val buf = freshBuf()
        msg.write(buf)
        assertEquals(msg, WorkbenchDocumentSnapshotClientMessage(buf))
    }
}
