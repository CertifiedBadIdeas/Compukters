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

package ru.lazyhat.compukterkraft.common.computer.client

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.util.concurrent.atomic.AtomicLong

interface ClientDisplayMetricsCollector {
    fun recordApply(
        frame: DisplayFrameDelta,
        accepted: Boolean,
        nanos: Long,
    )

    fun recordSwap(
        dirty: Boolean,
        nanos: Long,
    )

    fun recordSnapshotCopy(
        regions: List<ClientDisplayBuffer.Region>,
        width: Int,
        height: Int,
        nanos: Long,
    )

    fun recordNativeBatchApply(
        frameCount: Int,
        tileCount: Int,
        payloadBytes: Int,
        operationCount: Int,
        nanos: Long,
    )

    fun snapshot(): ClientDisplayProfilingSnapshot
}

data class ClientDisplayProfilingSnapshot(
    val framesReceived: Long = 0,
    val framesApplied: Long = 0,
    val rejectedFrames: Long = 0,
    val fullRefreshFrames: Long = 0,
    val tilesApplied: Long = 0,
    val payloadBytes: Long = 0,
    val applyNanos: Long = 0,
    val swapCalls: Long = 0,
    val dirtySwaps: Long = 0,
    val swapNanos: Long = 0,
    val snapshotsCopied: Long = 0,
    val snapshotRegions: Long = 0,
    val snapshotPixels: Long = 0,
    val snapshotCopyNanos: Long = 0,
    val nativeBatchesApplied: Long = 0,
    val nativeFramesApplied: Long = 0,
    val nativeTilesApplied: Long = 0,
    val nativePayloadBytes: Long = 0,
    val nativeOperationsApplied: Long = 0,
    val nativeApplyNanos: Long = 0,
) {
    val averageApplyNanos: Long get() = average(applyNanos, framesReceived)
    val averageSwapNanos: Long get() = average(swapNanos, swapCalls)
    val averageSnapshotCopyNanos: Long get() = average(snapshotCopyNanos, snapshotsCopied)
    val averageNativeApplyNanos: Long get() = average(nativeApplyNanos, nativeBatchesApplied)

    fun summary(): String =
        buildString {
            appendLine("client-display:")
            appendLine(
                "  decodedApply: received=$framesReceived, applied=$framesApplied, rejected=$rejectedFrames, " +
                    "fullRefresh=$fullRefreshFrames, tiles=$tilesApplied, payloadBytes=$payloadBytes, " +
                    "time=$applyNanos ns, avg=$averageApplyNanos ns",
            )
            appendLine(
                "  nativeBatchApply: batches=$nativeBatchesApplied, frames=$nativeFramesApplied, " +
                    "tiles=$nativeTilesApplied, payloadBytes=$nativePayloadBytes, operations=$nativeOperationsApplied, " +
                    "time=$nativeApplyNanos ns, avg=$averageNativeApplyNanos ns",
            )
            appendLine(
                "  swap: calls=$swapCalls, dirty=$dirtySwaps, time=$swapNanos ns, avg=$averageSwapNanos ns",
            )
            append(
                "  snapshotCopy: copies=$snapshotsCopied, regions=$snapshotRegions, pixels=$snapshotPixels, " +
                    "time=$snapshotCopyNanos ns, avg=$averageSnapshotCopyNanos ns",
            )
        }
}

object NoOpClientDisplayMetricsCollector : ClientDisplayMetricsCollector {
    override fun recordApply(
        frame: DisplayFrameDelta,
        accepted: Boolean,
        nanos: Long,
    ) = Unit

    override fun recordSwap(
        dirty: Boolean,
        nanos: Long,
    ) = Unit

    override fun recordSnapshotCopy(
        regions: List<ClientDisplayBuffer.Region>,
        width: Int,
        height: Int,
        nanos: Long,
    ) = Unit

    override fun recordNativeBatchApply(
        frameCount: Int,
        tileCount: Int,
        payloadBytes: Int,
        operationCount: Int,
        nanos: Long,
    ) = Unit

    override fun snapshot(): ClientDisplayProfilingSnapshot = ClientDisplayProfilingSnapshot()
}

class RecordingClientDisplayMetricsCollector : ClientDisplayMetricsCollector {
    private val framesReceived = AtomicLong()
    private val framesApplied = AtomicLong()
    private val rejectedFrames = AtomicLong()
    private val fullRefreshFrames = AtomicLong()
    private val tilesApplied = AtomicLong()
    private val payloadBytes = AtomicLong()
    private val applyNanos = AtomicLong()
    private val swapCalls = AtomicLong()
    private val dirtySwaps = AtomicLong()
    private val swapNanos = AtomicLong()
    private val snapshotsCopied = AtomicLong()
    private val snapshotRegions = AtomicLong()
    private val snapshotPixels = AtomicLong()
    private val snapshotCopyNanos = AtomicLong()
    private val nativeBatchesApplied = AtomicLong()
    private val nativeFramesApplied = AtomicLong()
    private val nativeTilesApplied = AtomicLong()
    private val nativePayloadBytes = AtomicLong()
    private val nativeOperationsApplied = AtomicLong()
    private val nativeApplyNanos = AtomicLong()

    override fun recordApply(
        frame: DisplayFrameDelta,
        accepted: Boolean,
        nanos: Long,
    ) {
        framesReceived.incrementAndGet()
        applyNanos.addAndGet(nanos.coerceAtLeast(0))
        if (!accepted) {
            rejectedFrames.incrementAndGet()
            return
        }
        framesApplied.incrementAndGet()
        if (frame.fullRefresh) {
            fullRefreshFrames.incrementAndGet()
        }
        tilesApplied.addAndGet(frame.tiles.size.toLong())
        payloadBytes.addAndGet(frame.tiles.sumOf { it.payload.size }.toLong())
    }

    override fun recordSwap(
        dirty: Boolean,
        nanos: Long,
    ) {
        swapCalls.incrementAndGet()
        swapNanos.addAndGet(nanos.coerceAtLeast(0))
        if (dirty) {
            dirtySwaps.incrementAndGet()
        }
    }

    override fun recordSnapshotCopy(
        regions: List<ClientDisplayBuffer.Region>,
        width: Int,
        height: Int,
        nanos: Long,
    ) {
        snapshotsCopied.incrementAndGet()
        snapshotCopyNanos.addAndGet(nanos.coerceAtLeast(0))
        snapshotRegions.addAndGet(regions.size.toLong())
        snapshotPixels.addAndGet(regions.sumOf { it.width.toLong() * it.height.toLong() })
    }

    override fun recordNativeBatchApply(
        frameCount: Int,
        tileCount: Int,
        payloadBytes: Int,
        operationCount: Int,
        nanos: Long,
    ) {
        nativeBatchesApplied.incrementAndGet()
        nativeFramesApplied.addAndGet(frameCount.coerceAtLeast(0).toLong())
        nativeTilesApplied.addAndGet(tileCount.coerceAtLeast(0).toLong())
        nativePayloadBytes.addAndGet(payloadBytes.coerceAtLeast(0).toLong())
        nativeOperationsApplied.addAndGet(operationCount.coerceAtLeast(0).toLong())
        nativeApplyNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun snapshot(): ClientDisplayProfilingSnapshot =
        ClientDisplayProfilingSnapshot(
            framesReceived = framesReceived.get(),
            framesApplied = framesApplied.get(),
            rejectedFrames = rejectedFrames.get(),
            fullRefreshFrames = fullRefreshFrames.get(),
            tilesApplied = tilesApplied.get(),
            payloadBytes = payloadBytes.get(),
            applyNanos = applyNanos.get(),
            swapCalls = swapCalls.get(),
            dirtySwaps = dirtySwaps.get(),
            swapNanos = swapNanos.get(),
            snapshotsCopied = snapshotsCopied.get(),
            snapshotRegions = snapshotRegions.get(),
            snapshotPixels = snapshotPixels.get(),
            snapshotCopyNanos = snapshotCopyNanos.get(),
            nativeBatchesApplied = nativeBatchesApplied.get(),
            nativeFramesApplied = nativeFramesApplied.get(),
            nativeTilesApplied = nativeTilesApplied.get(),
            nativePayloadBytes = nativePayloadBytes.get(),
            nativeOperationsApplied = nativeOperationsApplied.get(),
            nativeApplyNanos = nativeApplyNanos.get(),
        )
}

private fun average(
    total: Long,
    count: Long,
): Long = if (count <= 0) 0 else total / count
