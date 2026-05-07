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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayProfilingTest {
    @Test
    fun recordingCollectorCountsOperationsAndFrames() {
        val collector = RecordingDisplayMetricsCollector()
        collector.recordClear(displayId = 1, nanos = 0)
        collector.recordSetPixel(displayId = 1, nanos = 0)
        collector.recordFillRect(displayId = 1, width = 3, height = 4, nanos = 0)
        collector.recordCopyRect(displayId = 1, width = 4, height = 5, nanos = 0)
        collector.recordBlitMono(displayId = 1, width = 6, height = 7, nanos = 0)
        collector.recordPresent(displayId = 1, emittedFrame = true, nanos = 0)
        collector.recordFrameDrain(
            listOf(
                DisplayFrameDelta(
                    displayId = 1,
                    sequence = 1,
                    width = 16,
                    height = 16,
                    pixelFormat = DisplayPixelFormat.RGB565,
                    fullRefresh = false,
                    tiles =
                        listOf(
                            DisplayTile(
                                tileX = 0,
                                tileY = 0,
                                x = 0,
                                y = 0,
                                width = 2,
                                height = 2,
                                payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                            ),
                        ),
                ),
            ),
        )

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.operations.clearCalls)
        assertEquals(1, snapshot.operations.setPixelCalls)
        assertEquals(1, snapshot.operations.fillRectCalls)
        assertEquals(12, snapshot.operations.fillRectArea)
        assertEquals(1, snapshot.operations.copyRectCalls)
        assertEquals(20, snapshot.operations.copyRectArea)
        assertEquals(1, snapshot.operations.blitMonoCalls)
        assertEquals(42, snapshot.operations.blitMonoArea)
        assertEquals(1, snapshot.operations.presentCalls)
        assertEquals(1, snapshot.operations.presentFrames)
        assertEquals(1, snapshot.frames.frameCount)
        assertEquals(0, snapshot.frames.fullRefreshFrames)
        assertEquals(1, snapshot.frames.tileCount)
        assertEquals(8, snapshot.frames.payloadBytes)
    }

    @Test
    fun recordingCollectorAccumulatesOperationTimingsAndAverages() {
        val collector = RecordingDisplayMetricsCollector()

        collector.recordClear(displayId = 1, nanos = 10)
        collector.recordSetPixel(displayId = 1, nanos = 20)
        collector.recordFillRect(displayId = 1, width = 3, height = 4, nanos = 30)
        collector.recordCopyRect(displayId = 1, width = 4, height = 5, nanos = 40)
        collector.recordBlitMono(displayId = 1, width = 6, height = 7, nanos = 50)
        collector.recordPresent(displayId = 1, emittedFrame = true, nanos = 60)

        val snapshot = collector.snapshot()

        assertEquals(10, snapshot.operations.clearNanos)
        assertEquals(20, snapshot.operations.setPixelNanos)
        assertEquals(30, snapshot.operations.fillRectNanos)
        assertEquals(40, snapshot.operations.copyRectNanos)
        assertEquals(50, snapshot.operations.blitMonoNanos)
        assertEquals(60, snapshot.operations.presentNanos)
        assertEquals(30, snapshot.operations.averageFillRectNanos)
        assertEquals(40, snapshot.operations.averageCopyRectNanos)
        assertEquals(50, snapshot.operations.averageBlitMonoNanos)
        assertEquals(60, snapshot.operations.averagePresentNanos)
        val summary = snapshot.summary()
        assertTrue(summary.startsWith("display:\n"), summary)
        assertTrue(summary.contains("  operations:\n"), summary)
        assertTrue(summary.contains("    fillRect: count=1, area=12, time=30 ns, avg=30 ns"), summary)
        assertTrue(summary.contains("    blitMono: count=1, area=42, time=50 ns, avg=50 ns"), summary)
    }

    @Test
    fun recordingCollectorAccumulatesFrameBuildTimings() {
        val collector = RecordingDisplayMetricsCollector()

        collector.recordFrameBuild(
            displayId = 1,
            metrics =
                DisplayFrameBuildMetrics(
                    dirtyTileScanNanos = 10,
                    frameBuildNanos = 20,
                    tileSerializationNanos = 30,
                    frontCopyNanos = 40,
                    totalNanos = 100,
                    tileCount = 2,
                    payloadBytes = 16,
                ),
        )

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.frameBuild.buildCalls)
        assertEquals(10, snapshot.frameBuild.dirtyTileScanNanos)
        assertEquals(20, snapshot.frameBuild.frameBuildNanos)
        assertEquals(30, snapshot.frameBuild.tileSerializationNanos)
        assertEquals(40, snapshot.frameBuild.frontCopyNanos)
        assertEquals(100, snapshot.frameBuild.totalNanos)
        assertEquals(2, snapshot.frameBuild.tileCount)
        assertEquals(16, snapshot.frameBuild.payloadBytes)
        assertEquals(50, snapshot.frameBuild.averageTotalNanosPerTile)
        assertEquals(15, snapshot.frameBuild.averageTileSerializationNanosPerTile)
        assertEquals(1, snapshot.frameBuild.averageTileSerializationNanosPerPayloadByte)
        val summary = snapshot.summary()
        assertTrue(summary.contains("  frame-build:\n"), summary)
        assertTrue(summary.contains("    total: builds=1, time=100 ns, avg/build=100 ns, avg/tile=50 ns"), summary)
        assertTrue(summary.contains("    serialization: tiles=2, payload=16 B, avg/tile=15 ns, avg/byte=1 ns"), summary)
    }

    @Test
    fun noopCollectorKeepsEmptySnapshot() {
        val collector = NoOpDisplayMetricsCollector
        collector.recordClear(displayId = 1, nanos = 100)
        collector.recordSetPixel(displayId = 1, nanos = 100)
        collector.recordFillRect(displayId = 1, width = 3, height = 4, nanos = 100)
        collector.recordCopyRect(displayId = 1, width = 4, height = 5, nanos = 100)
        collector.recordBlitMono(displayId = 1, width = 6, height = 7, nanos = 100)
        collector.recordPresent(displayId = 1, emittedFrame = true, nanos = 100)
        collector.recordFrameDrain(emptyList())

        val snapshot = collector.snapshot()

        assertEquals(DisplayOperationMetrics(), snapshot.operations)
        assertEquals(DisplayFrameMetrics(), snapshot.frames)
        assertEquals(DisplayFrameBuildTotals(), snapshot.frameBuild)
    }

    @Test
    fun displayRegistryRecordsOperationsAndDrainedFrames() {
        val collector = RecordingDisplayMetricsCollector()
        val registry = DisplayRegistry(metricsCollector = collector)

        registry.attach(displayId = 7, width = 16, height = 16)
        registry.clear(displayId = 7, rgb565 = 0)
        registry.fillRect(displayId = 7, x = 0, y = 0, width = 5, height = 7, rgb565 = 0x07E0)
        registry.blitMono(displayId = 7, x = 1, y = 1, width = 3, height = 2, mask = "111000", foreground = 0x07E0, background = -1)
        registry.blitMono5x7(
            displayId = 7,
            x = 2,
            y = 2,
            row0 = 0b01110,
            row1 = 0b10001,
            row2 = 0b10001,
            row3 = 0b11111,
            row4 = 0b10001,
            row5 = 0b10001,
            row6 = 0b10001,
            foreground = 0x07E0,
            background = -1,
        )
        registry.copyRect(displayId = 7, srcX = 1, srcY = 1, width = 3, height = 2, dstX = 5, dstY = 5)
        registry.present(displayId = 7)
        val frames = registry.drainFrames()

        val snapshot = collector.snapshot()

        assertEquals(2, frames.size, "attach full-refresh plus present frame")
        assertEquals(1, snapshot.operations.clearCalls)
        assertEquals(1, snapshot.operations.fillRectCalls)
        assertEquals(35, snapshot.operations.fillRectArea)
        assertEquals(1, snapshot.operations.copyRectCalls)
        assertEquals(6, snapshot.operations.copyRectArea)
        assertEquals(2, snapshot.operations.blitMonoCalls)
        assertEquals(41, snapshot.operations.blitMonoArea)
        assertEquals(1, snapshot.operations.presentCalls)
        assertEquals(1, snapshot.operations.presentFrames)
        assertEquals(2, snapshot.frames.frameCount)
        assertEquals(1, snapshot.frames.fullRefreshFrames)
        assertEquals(frames.sumOf { it.tiles.size }.toLong(), snapshot.frames.tileCount)
        assertEquals(frames.sumOf { frame -> frame.tiles.sumOf { it.payload.size } }.toLong(), snapshot.frames.payloadBytes)
    }
}
