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
        val result = state.fullRefreshWithMetrics()
        pendingFrames.add(result.frame)
        metricsCollector.recordFrameBuild(displayId, result.metrics)
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
        val started = System.nanoTime()
        displays[displayId]?.clear(rgb565)
        metricsCollector.recordClear(displayId, System.nanoTime() - started)
    }

    fun setPixel(
        displayId: Int,
        x: Int,
        y: Int,
        rgb565: Int,
    ) {
        val started = System.nanoTime()
        displays[displayId]?.setPixel(x, y, rgb565)
        metricsCollector.recordSetPixel(displayId, System.nanoTime() - started)
    }

    fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) {
        val started = System.nanoTime()
        displays[displayId]?.fillRect(x, y, width, height, rgb565)
        metricsCollector.recordFillRect(displayId, width, height, System.nanoTime() - started)
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
        val started = System.nanoTime()
        displays[displayId]?.copyRect(srcX, srcY, width, height, dstX, dstY)
        metricsCollector.recordCopyRect(displayId, width, height, System.nanoTime() - started)
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
        val started = System.nanoTime()
        displays[displayId]?.blitMono(x, y, width, height, mask, foreground, background)
        metricsCollector.recordBlitMono(displayId, width, height, System.nanoTime() - started)
    }

    fun blitMono5x7(
        displayId: Int,
        x: Int,
        y: Int,
        row0: Int,
        row1: Int,
        row2: Int,
        row3: Int,
        row4: Int,
        row5: Int,
        row6: Int,
        foreground: Int,
        background: Int,
    ) {
        val started = System.nanoTime()
        displays[displayId]?.blitMono5x7(x, y, row0, row1, row2, row3, row4, row5, row6, foreground, background)
        metricsCollector.recordBlitMono(displayId, 5, 7, System.nanoTime() - started)
    }

    fun present(displayId: Int) {
        val started = System.nanoTime()
        val result = displays[displayId]?.presentWithMetrics()
        result?.let {
            pendingFrames.add(it.frame)
            metricsCollector.recordFrameBuild(displayId, it.metrics)
        }
        metricsCollector.recordPresent(displayId, emittedFrame = result != null, nanos = System.nanoTime() - started)
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
