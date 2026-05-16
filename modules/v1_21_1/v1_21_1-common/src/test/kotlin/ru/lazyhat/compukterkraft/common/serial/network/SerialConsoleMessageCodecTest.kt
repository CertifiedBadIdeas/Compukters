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
package ru.lazyhat.compukterkraft.common.serial.network

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.serial.network.client.SerialConsoleOutputClientMessage
import ru.lazyhat.compukterkraft.common.serial.network.server.SerialConsoleInputServerMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SerialConsoleMessageCodecTest {
    private fun freshBuf(): FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer())

    @Test
    fun `input message round trips container and bytes`() {
        val message = SerialConsoleInputServerMessage(containerId = 7, bytes = "ping\n".encodeToByteArray())
        val buf = freshBuf()

        message.write(buf)
        val restored = SerialConsoleInputServerMessage(buf)

        assertEquals(7, restored.containerId)
        assertContentEquals("ping\n".encodeToByteArray(), restored.bytes)
        assertEquals(0, buf.readableBytes())
    }

    @Test
    fun `output message round trips reset flag and bytes`() {
        val message =
            SerialConsoleOutputClientMessage(
                containerId = 12,
                bytes = "RUX READY\n".encodeToByteArray(),
                reset = true,
            )
        val buf = freshBuf()

        message.write(buf)
        val restored = SerialConsoleOutputClientMessage(buf)

        assertEquals(12, restored.containerId)
        assertContentEquals("RUX READY\n".encodeToByteArray(), restored.bytes)
        assertEquals(true, restored.reset)
        assertEquals(0, buf.readableBytes())
    }
}
