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

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayInfo
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import java.util.concurrent.ConcurrentHashMap

class DisplayRegistry {
    private val displays = ConcurrentHashMap<Int, DisplayInfo>()

    fun attach(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
    ): DisplayInfo {
        require(width in MIN_SIZE..MAX_SIZE) { "Display width out of range: $width" }
        require(height in MIN_SIZE..MAX_SIZE) { "Display height out of range: $height" }
        val info = DisplayInfo(displayId, width, height, pixelFormat)
        displays[displayId] = info
        return info
    }

    fun resize(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
    ): DisplayInfo = attach(displayId, width, height, pixelFormat)

    fun detach(displayId: Int) {
        displays.remove(displayId)
    }

    fun firstDisplayId(): Int = displays.keys.minOrNull() ?: -1

    fun info(displayId: Int): DisplayInfo? = displays[displayId]

    companion object {
        const val MIN_SIZE = 1
        const val MAX_SIZE = 4096
    }
}
