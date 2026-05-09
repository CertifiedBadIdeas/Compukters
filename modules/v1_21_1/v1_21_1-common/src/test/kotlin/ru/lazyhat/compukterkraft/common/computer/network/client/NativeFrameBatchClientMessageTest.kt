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
package ru.lazyhat.compukterkraft.common.computer.network.client

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NativeFrameBatchClientMessageTest {
    @Test
    fun roundTripsContainerIdAndPayload() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val original = NativeFrameBatchClientMessage(containerId = 42, payload = payload)
        val buffer = FriendlyByteBuf(Unpooled.buffer())

        original.write(buffer)
        val decoded = NativeFrameBatchClientMessage(buffer)

        assertEquals(42, decoded.containerId)
        assertContentEquals(payload, decoded.payload)
    }
}
