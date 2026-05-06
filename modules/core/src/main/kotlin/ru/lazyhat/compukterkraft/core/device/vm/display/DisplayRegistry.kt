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
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayInfo
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class DisplayRegistry(
    private val metricsCollector: DisplayMetricsCollector = NoOpDisplayMetricsCollector,
) {
    private val displays = ConcurrentHashMap<Int, DisplayState>()
    private val pendingFrames = ConcurrentLinkedQueue<DisplayFrameDelta>()

    fun attach(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
    ): DisplayInfo {
        require(width in MIN_SIZE..MAX_SIZE) { "Display width out of range: $width" }
        require(height in MIN_SIZE..MAX_SIZE) { "Display height out of range: $height" }
        val state = DisplayState(displayId, width, height, pixelFormat)
        displays[displayId] = state
        pendingFrames.add(state.fullRefresh())
        return info(state)
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

    fun info(displayId: Int): DisplayInfo? = displays[displayId]?.let(::info)

    fun clear(
        displayId: Int,
        rgb565: Int,
    ) {
        metricsCollector.recordClear(displayId)
        displays[displayId]?.clear(rgb565)
    }

    fun setPixel(
        displayId: Int,
        x: Int,
        y: Int,
        rgb565: Int,
    ) {
        metricsCollector.recordSetPixel(displayId)
        displays[displayId]?.setPixel(x, y, rgb565)
    }

    fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) {
        metricsCollector.recordFillRect(displayId, width, height)
        displays[displayId]?.fillRect(x, y, width, height, rgb565)
    }

    fun copyRect(
        displayId: Int,
        srcX: Int,
        srcY: Int,
        width: Int,
        height: Int,
        dstX: Int,
        dstY: Int,
    ) {
        metricsCollector.recordCopyRect(displayId, width, height)
        displays[displayId]?.copyRect(srcX, srcY, width, height, dstX, dstY)
    }

    fun blitMono(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mask: String,
        foreground: Int,
        background: Int,
    ) {
        metricsCollector.recordBlitMono(displayId, width, height)
        displays[displayId]?.blitMono(x, y, width, height, mask, foreground, background)
    }

    fun present(displayId: Int) {
        val frame = displays[displayId]?.present()
        metricsCollector.recordPresent(displayId, emittedFrame = frame != null)
        frame?.let(pendingFrames::add)
    }

    fun drainFrames(): List<DisplayFrameDelta> =
        buildList {
            while (true) {
                add(pendingFrames.poll() ?: break)
            }
        }.also(metricsCollector::recordFrameDrain)

    private fun info(state: DisplayState): DisplayInfo = DisplayInfo(state.displayId, state.width, state.height, state.pixelFormat)

    companion object {
        const val MIN_SIZE = 1
        const val MAX_SIZE = 4096
    }
}
