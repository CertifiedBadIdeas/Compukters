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

import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeVmBindings
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayInfo
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat

class NativeDisplayRegistry(
    private val kernelHandle: Long,
) {
    fun attach(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
    ): DisplayInfo {
        require(pixelFormat == DisplayPixelFormat.RGB565) { "Native display supports RGB565 only" }
        NativeVmBindings.attachNativeDisplay(kernelHandle, displayId, width, height)
        return DisplayInfo(displayId, width, height, pixelFormat)
    }

    fun detach(displayId: Int) {
        NativeVmBindings.detachNativeDisplay(kernelHandle, displayId)
    }

    fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) {
        NativeVmBindings.nativeDisplayFillRect(kernelHandle, displayId, x, y, width, height, rgb565)
    }

    fun present(displayId: Int) {
        NativeVmBindings.nativeDisplayPresent(kernelHandle, displayId)
    }

    fun drainFrames(): List<DisplayFrameDelta> =
        NativeDisplayFrameCodec.decodeFrames(NativeVmBindings.drainNativeDisplayFrames(kernelHandle))
}
