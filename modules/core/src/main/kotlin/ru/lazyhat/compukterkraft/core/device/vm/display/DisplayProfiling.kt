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
    fun recordFrameDrain(frames: List<DisplayFrameDelta>)

    fun snapshot(): DisplayProfilingSnapshot
}

data class DisplayOperationMetrics(
    val clearCalls: Long = 0,
    val clearNanos: Long = 0,
    val setPixelCalls: Long = 0,
    val setPixelNanos: Long = 0,
    val fillRectCalls: Long = 0,
    val fillRectArea: Long = 0,
    val fillRectNanos: Long = 0,
    val copyRectCalls: Long = 0,
    val copyRectArea: Long = 0,
    val copyRectNanos: Long = 0,
    val blitMonoCalls: Long = 0,
    val blitMonoArea: Long = 0,
    val blitMonoNanos: Long = 0,
    val presentCalls: Long = 0,
    val presentFrames: Long = 0,
    val presentNanos: Long = 0,
) {
    val averageClearNanos: Long get() = average(clearNanos, clearCalls)
    val averageSetPixelNanos: Long get() = average(setPixelNanos, setPixelCalls)
    val averageFillRectNanos: Long get() = average(fillRectNanos, fillRectCalls)
    val averageCopyRectNanos: Long get() = average(copyRectNanos, copyRectCalls)
    val averageBlitMonoNanos: Long get() = average(blitMonoNanos, blitMonoCalls)
    val averagePresentNanos: Long get() = average(presentNanos, presentCalls)

    val allCalls = clearCalls + setPixelCalls + fillRectCalls + copyRectCalls + blitMonoCalls + presentCalls
    val allNanos = clearNanos + setPixelNanos + fillRectNanos + copyRectNanos + blitMonoNanos + presentNanos
}

private fun average(
    total: Long,
    count: Long,
): Long = if (count <= 0) 0 else total / count

data class DisplayFrameMetrics(
    val frameCount: Long = 0,
    val fullRefreshFrames: Long = 0,
    val tileCount: Long = 0,
    val payloadBytes: Long = 0,
)

data class DisplayFrameBuildTotals(
    val buildCalls: Long = 0,
    val dirtyTileScanNanos: Long = 0,
    val frameBuildNanos: Long = 0,
    val tileSerializationNanos: Long = 0,
    val frontCopyNanos: Long = 0,
    val totalNanos: Long = 0,
    val tileCount: Long = 0,
    val payloadBytes: Long = 0,
) {
    val averageTotalNanosPerBuild: Long get() = average(totalNanos, buildCalls)
    val averageTotalNanosPerTile: Long get() = average(totalNanos, tileCount)
    val averageTileSerializationNanosPerTile: Long get() = average(tileSerializationNanos, tileCount)
    val averageTileSerializationNanosPerPayloadByte: Long get() = average(tileSerializationNanos, payloadBytes)
}

data class DisplayProfilingSnapshot(
    val operations: DisplayOperationMetrics = DisplayOperationMetrics(),
    val frames: DisplayFrameMetrics = DisplayFrameMetrics(),
    val frameBuild: DisplayFrameBuildTotals = DisplayFrameBuildTotals(),
) {
    fun summary(): String =
        buildString {
            appendLine("display:")
            appendLine("  operations: count=${operations.allCalls}, time=${operations.allNanos.nanos()}")
            appendLine(
                "    clear: count=${operations.clearCalls}, time=${operations.clearNanos.nanos()}, avg=${operations.averageClearNanos.nanos()}",
            )
            appendLine(
                "    setPixel: count=${operations.setPixelCalls}, time=${operations.setPixelNanos.nanos()}, avg=${operations.averageSetPixelNanos.nanos()}",
            )
            appendLine(
                "    fillRect: count=${operations.fillRectCalls}, area=${operations.fillRectArea}, time=${operations.fillRectNanos.nanos()}, avg=${operations.averageFillRectNanos.nanos()}",
            )
            appendLine(
                "    copyRect: count=${operations.copyRectCalls}, area=${operations.copyRectArea}, time=${operations.copyRectNanos.nanos()}, avg=${operations.averageCopyRectNanos.nanos()}",
            )
            appendLine(
                "    blitMono: count=${operations.blitMonoCalls}, area=${operations.blitMonoArea}, time=${operations.blitMonoNanos.nanos()}, avg=${operations.averageBlitMonoNanos.nanos()}",
            )
            appendLine(
                "    present: count=${operations.presentCalls}, frames=${operations.presentFrames}, time=${operations.presentNanos.nanos()}, avg=${operations.averagePresentNanos.nanos()}",
            )
            appendLine("  frames:")
            appendLine(
                "    emitted: count=${frames.frameCount}, fullRefresh=${frames.fullRefreshFrames}, tiles=${frames.tileCount}, payload=${frames.payloadBytes.bytes()}",
            )
            appendLine("  frame-build:")
            appendLine(
                "    total: builds=${frameBuild.buildCalls}, time=${frameBuild.totalNanos.nanos()}, avg/build=${frameBuild.averageTotalNanosPerBuild.nanos()}, avg/tile=${frameBuild.averageTotalNanosPerTile.nanos()}",
            )
            appendLine(
                "    phases: dirtyScan=${frameBuild.dirtyTileScanNanos.nanos()}, frameBuild=${frameBuild.frameBuildNanos.nanos()}, tileSerialization=${frameBuild.tileSerializationNanos.nanos()}, frontCopy=${frameBuild.frontCopyNanos.nanos()}",
            )
            append(
                "    serialization: tiles=${frameBuild.tileCount}, payload=${frameBuild.payloadBytes.bytes()}, avg/tile=${frameBuild.averageTileSerializationNanosPerTile.nanos()}, avg/byte=${frameBuild.averageTileSerializationNanosPerPayloadByte.nanos()}",
            )
        }
}

private fun Long.nanos(): String = "$this ns"

private fun Long.bytes(): String = "$this B"

object NoOpDisplayMetricsCollector : DisplayMetricsCollector {
    override fun recordFrameDrain(frames: List<DisplayFrameDelta>) = Unit

    override fun snapshot(): DisplayProfilingSnapshot = DisplayProfilingSnapshot()
}

class RecordingDisplayMetricsCollector : DisplayMetricsCollector {
    private val frameCount = AtomicLong()
    private val fullRefreshFrames = AtomicLong()
    private val tileCount = AtomicLong()
    private val payloadBytes = AtomicLong()

    override fun recordFrameDrain(frames: List<DisplayFrameDelta>) {
        frameCount.addAndGet(frames.size.toLong())
        fullRefreshFrames.addAndGet(frames.count { it.fullRefresh }.toLong())
        tileCount.addAndGet(frames.sumOf { it.tiles.size }.toLong())
        payloadBytes.addAndGet(frames.sumOf { frame -> frame.tiles.sumOf { it.payload.size } }.toLong())
    }

    override fun snapshot(): DisplayProfilingSnapshot =
        DisplayProfilingSnapshot(
            frames =
                DisplayFrameMetrics(
                    frameCount = frameCount.get(),
                    fullRefreshFrames = fullRefreshFrames.get(),
                    tileCount = tileCount.get(),
                    payloadBytes = payloadBytes.get(),
                ),
        )
}
