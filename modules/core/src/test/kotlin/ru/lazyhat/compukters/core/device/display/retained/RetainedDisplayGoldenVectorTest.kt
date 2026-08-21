/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.core.device.display.retained

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RetainedDisplayGoldenVectorTest {
    @Test
    fun installsRustEmptySnapshotAndReturnsExactAck() {
        val snapshot =
            hex(
                """
                4b 44 53 50 01 00 01 00 30 00 00 00 2a 00 00 00
                07 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
                00 00 00 00 08 00 00 00 00 00 00 00 00 00 00 00
                """,
            )
        val expectedAck =
            hex(
                """
                4b 44 53 50 01 00 03 00 20 00 00 00 2a 00 00 00
                07 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
                """,
            )

        val result = assertIs<RetainedDisplayApplyResult.Installed>(RetainedDisplayReplica().apply(snapshot))

        assertContentEquals(expectedAck, result.acknowledgement)
        assertEquals(42u, result.state.computerId)
        assertEquals(7uL, result.state.viewerEpoch)
        assertEquals(0uL, result.state.sequence)
        assertEquals(emptyList(), result.state.resources)
        assertEquals(emptyList(), result.state.drawList.commands)
    }

    @Test
    fun encodesAllExactResyncReasons() {
        for (reason in RetainedDisplayResyncReason.entries) {
            val bytes = RetainedDisplayProtocol.encodeResyncRequest(42u, 7uL, 9uL, reason)

            assertEquals(40, bytes.size)
            assertEquals(4, u16(bytes, 6))
            assertEquals(42, u32(bytes, 12))
            assertEquals(7L, u64(bytes, 16))
            assertEquals(9L, u64(bytes, 24))
            assertEquals(reason.code, u16(bytes, 32))
            assertEquals(1, u16(bytes, 34))
            assertEquals(0, u32(bytes, 36))
        }
    }

    @Test
    fun appliesRustImageSnapshotAndPatchDelta() {
        val snapshot =
            hex(
                """
                4b44535001000100600000002a00000007000000000000000100000000000000
                0100000024000000010000001400000001000000020001003412cdab00000000
                01000000200000001c0000000100000000000000020001000000000002000100
                """,
            )
        val patch =
            hex(
                """
                4b445350010002004c0000002a00000007000000000000000100000000000000
                02000000000000000100000000000000100000001c0000000100000001000000
                000000000100010021430000
                """,
            )
        val replica = RetainedDisplayReplica()

        replica.apply(snapshot)
        val installed = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(patch))

        assertEquals(0x4321, assertIs<RetainedImageRgb565>(installed.state.resource(1u)?.content).pixelAt(0, 0))
        assertEquals(2uL, installed.state.sequence)
    }

    @Test
    fun appliesRustCoalescedRecreationAndRebindsEqualIdList() {
        val snapshot =
            hex(
                """
                4b44535001000100600000002a00000007000000000000000100000000000000
                0100000024000000010000001400000001000000020001003412cdab00000000
                01000000200000001c0000000100000000000000020001000000000002000100
                """,
            )
        val recreate =
            hex(
                """
                4b44535001000200740000002a00000007000000000000000100000000000000
                03000000000000000200000024000000200000000c0000000100000001000000
                140000000100000002000100999988880000000001000000200000001c000000
                0100000000000000020001000000000002000100
                """,
            )
        val replica = RetainedDisplayReplica()
        val initial = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(snapshot))
        val oldIdentity = initial.state.resource(1u)!!.localIdentity

        val installed = assertIs<RetainedDisplayApplyResult.Installed>(replica.apply(recreate))
        val newResource = installed.state.resource(1u)!!
        val binding =
            assertIs<RetainedDrawCommand.DrawImage>(
                installed.state.drawList.commands
                    .single(),
            ).image

        assertEquals(3uL, installed.state.sequence)
        assertEquals(newResource.localIdentity, binding.localIdentity)
        kotlin.test.assertNotEquals(oldIdentity, newResource.localIdentity)
    }

    private fun hex(text: String): ByteArray =
        text
            .filterNot(Char::isWhitespace)
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun u16(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(
        bytes: ByteArray,
        offset: Int,
    ): Int = u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)

    private fun u64(
        bytes: ByteArray,
        offset: Int,
    ): Long = u32(bytes, offset).toLong() and 0xffff_ffffL or (u32(bytes, offset + 4).toLong() shl 32)
}
