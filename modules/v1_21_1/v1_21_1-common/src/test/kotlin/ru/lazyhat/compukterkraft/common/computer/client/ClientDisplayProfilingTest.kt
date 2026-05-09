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
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientDisplayProfilingTest {
    @Test
    fun clientDisplayBufferRecordsApplySwapAndSnapshotWork() {
        val metrics = RecordingClientDisplayMetricsCollector()
        val buffer = ClientDisplayBuffer(displayId = 7, width = 2, height = 2, metricsCollector = metrics)
        val frame =
            DisplayFrameDelta(
                displayId = 7,
                sequence = 1,
                width = 2,
                height = 2,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = true,
                tiles =
                    listOf(
                        DisplayTile(
                            tileX = 0,
                            tileY = 0,
                            x = 0,
                            y = 0,
                            width = 2,
                            height = 2,
                            payload = byteArrayOf(0, 0, 0, 1, 0, 2, 0, 3),
                        ),
                    ),
            )

        assertTrue(buffer.apply(frame))
        assertTrue(buffer.swapIfDirty())
        buffer.copyFrontSnapshotSince(uploadedVersion = 0)

        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.framesApplied)
        assertEquals(1, snapshot.fullRefreshFrames)
        assertEquals(1, snapshot.tilesApplied)
        assertEquals(8, snapshot.payloadBytes)
        assertEquals(1, snapshot.swapCalls)
        assertEquals(1, snapshot.snapshotsCopied)
        assertEquals(4, snapshot.snapshotPixels)
        assertTrue(snapshot.applyNanos >= 0)
        assertTrue(snapshot.swapNanos >= 0)
        assertTrue(snapshot.snapshotCopyNanos >= 0)
    }
}
