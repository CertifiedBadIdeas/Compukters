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
import java.util.concurrent.atomic.AtomicLong

interface DisplayMetricsCollector {
    fun recordClear(displayId: Int)

    fun recordSetPixel(displayId: Int)

    fun recordFillRect(
        displayId: Int,
        width: Int,
        height: Int,
    )

    fun recordPresent(
        displayId: Int,
        emittedFrame: Boolean,
    )

    fun recordFrameDrain(frames: List<DisplayFrameDelta>)

    fun snapshot(): DisplayProfilingSnapshot
}

data class DisplayOperationMetrics(
    val clearCalls: Long = 0,
    val setPixelCalls: Long = 0,
    val fillRectCalls: Long = 0,
    val fillRectArea: Long = 0,
    val presentCalls: Long = 0,
    val presentFrames: Long = 0,
)

data class DisplayFrameMetrics(
    val frameCount: Long = 0,
    val fullRefreshFrames: Long = 0,
    val tileCount: Long = 0,
    val payloadBytes: Long = 0,
)

data class DisplayProfilingSnapshot(
    val operations: DisplayOperationMetrics = DisplayOperationMetrics(),
    val frames: DisplayFrameMetrics = DisplayFrameMetrics(),
) {
    fun summary(): String =
        "display: clear=${operations.clearCalls}, setPixel=${operations.setPixelCalls}, " +
            "fillRect=${operations.fillRectCalls}, fillArea=${operations.fillRectArea}, " +
            "present=${operations.presentCalls}, presentFrames=${operations.presentFrames}\n" +
            "frames: count=${frames.frameCount}, fullRefresh=${frames.fullRefreshFrames}, " +
            "tiles=${frames.tileCount}, payloadBytes=${frames.payloadBytes}"
}

object NoOpDisplayMetricsCollector : DisplayMetricsCollector {
    override fun recordClear(displayId: Int) = Unit

    override fun recordSetPixel(displayId: Int) = Unit

    override fun recordFillRect(
        displayId: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun recordPresent(
        displayId: Int,
        emittedFrame: Boolean,
    ) = Unit

    override fun recordFrameDrain(frames: List<DisplayFrameDelta>) = Unit

    override fun snapshot(): DisplayProfilingSnapshot = DisplayProfilingSnapshot()
}

class RecordingDisplayMetricsCollector : DisplayMetricsCollector {
    private val clearCalls = AtomicLong()
    private val setPixelCalls = AtomicLong()
    private val fillRectCalls = AtomicLong()
    private val fillRectArea = AtomicLong()
    private val presentCalls = AtomicLong()
    private val presentFrames = AtomicLong()
    private val frameCount = AtomicLong()
    private val fullRefreshFrames = AtomicLong()
    private val tileCount = AtomicLong()
    private val payloadBytes = AtomicLong()

    override fun recordClear(displayId: Int) {
        clearCalls.incrementAndGet()
    }

    override fun recordSetPixel(displayId: Int) {
        setPixelCalls.incrementAndGet()
    }

    override fun recordFillRect(
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        fillRectCalls.incrementAndGet()
        if (width > 0 && height > 0) {
            fillRectArea.addAndGet(width.toLong() * height.toLong())
        }
    }

    override fun recordPresent(
        displayId: Int,
        emittedFrame: Boolean,
    ) {
        presentCalls.incrementAndGet()
        if (emittedFrame) {
            presentFrames.incrementAndGet()
        }
    }

    override fun recordFrameDrain(frames: List<DisplayFrameDelta>) {
        frameCount.addAndGet(frames.size.toLong())
        fullRefreshFrames.addAndGet(frames.count { it.fullRefresh }.toLong())
        tileCount.addAndGet(frames.sumOf { it.tiles.size }.toLong())
        payloadBytes.addAndGet(frames.sumOf { frame -> frame.tiles.sumOf { it.payload.size } }.toLong())
    }

    override fun snapshot(): DisplayProfilingSnapshot =
        DisplayProfilingSnapshot(
            operations =
                DisplayOperationMetrics(
                    clearCalls = clearCalls.get(),
                    setPixelCalls = setPixelCalls.get(),
                    fillRectCalls = fillRectCalls.get(),
                    fillRectArea = fillRectArea.get(),
                    presentCalls = presentCalls.get(),
                    presentFrames = presentFrames.get(),
                ),
            frames =
                DisplayFrameMetrics(
                    frameCount = frameCount.get(),
                    fullRefreshFrames = fullRefreshFrames.get(),
                    tileCount = tileCount.get(),
                    payloadBytes = payloadBytes.get(),
                ),
        )
}
