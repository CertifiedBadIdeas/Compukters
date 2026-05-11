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

class DisplayProfilingTest {
    @Test
    fun recordingCollectorCountsDrainedNativeFrames() {
        val collector = RecordingDisplayMetricsCollector()

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
                DisplayFrameDelta(
                    displayId = 1,
                    sequence = 2,
                    width = 16,
                    height = 16,
                    pixelFormat = DisplayPixelFormat.RGB565,
                    fullRefresh = true,
                    tiles = emptyList(),
                ),
            ),
        )

        val snapshot = collector.snapshot()

        assertEquals(2, snapshot.frames.frameCount)
        assertEquals(1, snapshot.frames.fullRefreshFrames)
        assertEquals(1, snapshot.frames.tileCount)
        assertEquals(8, snapshot.frames.payloadBytes)
    }

    @Test
    fun noopCollectorKeepsEmptySnapshot() {
        val collector = NoOpDisplayMetricsCollector

        collector.recordFrameDrain(emptyList())

        assertEquals(DisplayProfilingSnapshot(), collector.snapshot())
    }

    @Test
    fun displayRegistryStoresOnlyDisplayMetadata() {
        val registry = DisplayRegistry()

        val info = registry.attach(displayId = 7, width = 16, height = 16)

        assertEquals(info, registry.info(displayId = 7))
        assertEquals(7, registry.firstDisplayId())
        registry.detach(displayId = 7)
        assertEquals(null, registry.info(displayId = 7))
        assertEquals(-1, registry.firstDisplayId())
    }
}
