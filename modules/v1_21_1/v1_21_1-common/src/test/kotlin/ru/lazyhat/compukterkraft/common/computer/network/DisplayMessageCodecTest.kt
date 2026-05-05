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
import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.computer.network.client.FrameDeltaClientMessage
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayMessageCodecTest {
    private fun freshBuf(): FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer())

    @Test
    fun frameDeltaClientMessageRoundTripsTiles() {
        val frame =
            DisplayFrameDelta(
                displayId = 9,
                sequence = 12L,
                width = 32,
                height = 16,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = false,
                tiles = listOf(DisplayTile(0, 0, 0, 0, 16, 16, byteArrayOf(1, 2, 3, 4))),
            )
        val message = FrameDeltaClientMessage(containerId = 4, frame = frame)
        val buf = freshBuf()

        message.write(buf)
        val restored = FrameDeltaClientMessage(buf)

        assertEquals(4, restored.containerId)
        assertEquals(9, restored.frame.displayId)
        assertEquals(12L, restored.frame.sequence)
        assertEquals(DisplayPixelFormat.RGB565, restored.frame.pixelFormat)
        assertTrue(restored.frame.tiles.single().payload.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }
}