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

package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameOperation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeDisplayFrameCodecTest {
    @Test
    fun decodesDisplayFrameOperationsAfterTiles() {
        val bytes =
            ByteBuffer
                .allocate(4 + 4 + 8 + 4 + 4 + 1 + 1 + 4 + 4 + 1 + 5 * 4 + 1 + 6 * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1)
                .putInt(7)
                .putLong(42)
                .putInt(320)
                .putInt(200)
                .put(0)
                .put(0)
                .putInt(0)
                .putInt(2)
                .put(1)
                .putInt(0)
                .putInt(192)
                .putInt(320)
                .putInt(8)
                .putInt(0x07E0)
                .put(2)
                .putInt(0)
                .putInt(8)
                .putInt(320)
                .putInt(192)
                .putInt(0)
                .putInt(0)
                .array()

        val frame = NativeDisplayFrameCodec.decodeFrames(bytes).single()

        assertEquals(
            listOf(
                DisplayFrameOperation.FillRect(x = 0, y = 192, width = 320, height = 8, rgb565 = 0x07E0),
                DisplayFrameOperation.CopyRect(srcX = 0, srcY = 8, width = 320, height = 192, dstX = 0, dstY = 0),
            ),
            frame.operations,
        )
    }
}
