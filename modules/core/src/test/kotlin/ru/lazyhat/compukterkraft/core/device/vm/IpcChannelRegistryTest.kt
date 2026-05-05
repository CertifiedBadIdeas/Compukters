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

package ru.lazyhat.compukterkraft.core.device.vm

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IpcChannelRegistryTest {
    @Test
    fun tryReadReturnsAvailableTextWithoutBlocking() =
        runTest {
            val registry = IpcChannelRegistry(maxBufferedBytesPerChannel = 16)
            val channel = registry.open()

            registry.write(channel, "hello")

            assertEquals("hello", registry.tryRead(channel))
            assertEquals("", registry.tryRead(channel))
        }

    @Test
    fun readSuspendsUntilTextIsWritten() =
        runTest {
            val registry = IpcChannelRegistry(maxBufferedBytesPerChannel = 16)
            val channel = registry.open()

            val read = async { registry.read(channel) }
            registry.write(channel, "ready")

            assertEquals("ready", read.await())
        }

    @Test
    fun closeWakesReadersWithEmptyText() =
        runTest {
            val registry = IpcChannelRegistry(maxBufferedBytesPerChannel = 16)
            val channel = registry.open()

            val read = async { registry.read(channel) }
            registry.close(channel)

            assertEquals("", read.await())
        }

    @Test
    fun enforcesBoundedBuffering() =
        runTest {
            val registry = IpcChannelRegistry(maxBufferedBytesPerChannel = 4)
            val channel = registry.open()

            assertFailsWith<IllegalStateException> {
                registry.write(channel, "12345")
            }
        }
}