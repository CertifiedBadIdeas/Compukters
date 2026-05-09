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

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NativeDisplayFrameCodec {
    fun decodeFrames(bytes: ByteArray): List<DisplayFrameDelta> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = input.int
        return List(count) {
            val displayId = input.int
            val sequence = input.long
            val width = input.int
            val height = input.int
            val pixelFormat =
                when (input.get().toInt()) {
                    0 -> DisplayPixelFormat.RGB565
                    else -> error("Unknown native display pixel format")
                }
            val fullRefresh = input.get().toInt() != 0
            val tileCount = input.int
            val tiles =
                List(tileCount) {
                    val tileX = input.int
                    val tileY = input.int
                    val x = input.int
                    val y = input.int
                    val tileWidth = input.int
                    val tileHeight = input.int
                    val payloadLength = input.int
                    val payload = ByteArray(payloadLength)
                    input.get(payload)
                    DisplayTile(tileX, tileY, x, y, tileWidth, tileHeight, payload)
                }
            DisplayFrameDelta(displayId, sequence, width, height, pixelFormat, fullRefresh, tiles)
        }
    }
}
