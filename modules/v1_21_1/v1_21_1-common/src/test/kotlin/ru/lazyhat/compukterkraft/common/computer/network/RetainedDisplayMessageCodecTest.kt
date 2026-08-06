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

package ru.lazyhat.compukterkraft.common.computer.network

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.Level
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayAttachServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayBinding
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayControlServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayDetachServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayStateClientMessage
import ru.lazyhat.compukterkraft.common.network.MessageTypeImpl
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RetainedDisplayMessageCodecTest {
    @Test
    fun registersOnlyTheFourRetainedDisplayPurposesInTheReusedRange() {
        assertEquals(22, (NetworkMessages.RETAINED_DISPLAY_ATTACH as MessageTypeImpl<*>).id)
        assertEquals(23, (NetworkMessages.RETAINED_DISPLAY_DETACH as MessageTypeImpl<*>).id)
        assertEquals(24, (NetworkMessages.RETAINED_DISPLAY_CONTROL as MessageTypeImpl<*>).id)
        assertEquals(25, (NetworkMessages.RETAINED_DISPLAY_STATE as MessageTypeImpl<*>).id)
        assertFalse(NetworkMessages.serverbound.any { (it as MessageTypeImpl<*>).id == 26 })
        assertFalse(NetworkMessages.clientbound.any { (it as MessageTypeImpl<*>).id == 26 })
    }

    @Test
    fun roundTripsMenuAndNotebookAttachBindings() {
        val menu = roundTrip(RetainedDisplayAttachServerMessage(42, RetainedDisplayBinding.Menu(7)))
        assertEquals(42, menu.computerId)
        assertEquals(RetainedDisplayBinding.Menu(7), menu.binding)

        val notebookBinding = RetainedDisplayBinding.Notebook(Level.OVERWORLD, BlockPos(1, 64, -3))
        val notebook = roundTrip(RetainedDisplayAttachServerMessage(42, notebookBinding))
        assertEquals(notebookBinding, notebook.binding)
    }

    @Test
    fun roundTripsBoundedDetachControlAndStatePayloads() {
        assertEquals(42, roundTrip(RetainedDisplayDetachServerMessage(42)).computerId)

        for (size in listOf(32, 40)) {
            val payload = ByteArray(size) { it.toByte() }
            val restored = roundTrip(RetainedDisplayControlServerMessage(42, payload))
            assertEquals(42, restored.computerId)
            assertContentEquals(payload, restored.payload)
        }

        val payload = ByteArray(524_288) { (it and 0xff).toByte() }
        val restored = roundTrip(RetainedDisplayStateClientMessage(42, payload))
        assertEquals(42, restored.computerId)
        assertContentEquals(payload, restored.payload)
    }

    @Test
    fun rejectsInvalidIdsPayloadSizesAndBindingKinds() {
        assertFailsWith<IllegalArgumentException> {
            RetainedDisplayAttachServerMessage(0, RetainedDisplayBinding.Menu(7))
        }
        assertFailsWith<IllegalArgumentException> { RetainedDisplayDetachServerMessage(-1) }
        assertFailsWith<IllegalArgumentException> { RetainedDisplayControlServerMessage(1, ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { RetainedDisplayControlServerMessage(1, ByteArray(41)) }
        assertFailsWith<IllegalArgumentException> { RetainedDisplayStateClientMessage(1, ByteArray(524_289)) }

        val buffer = FriendlyByteBuf(Unpooled.buffer())
        buffer.writeVarInt(42)
        buffer.writeByte(127)
        assertFailsWith<IllegalArgumentException> { RetainedDisplayAttachServerMessage(buffer) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : NetworkMessage<*>> roundTrip(message: T): T {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        message.write(buffer)
        val type = message.type() as MessageTypeImpl<T>
        return type.reader(buffer)
    }
}
