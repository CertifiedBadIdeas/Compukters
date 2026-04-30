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

package ru.lazyhat.compukterkraft.core.device.vm.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComputerStdioBroadcasterTest {
    private class RecordingConsumer : ComputerStdioBroadcaster.Consumer {
        val chunks = mutableListOf<ByteArray>()

        override fun enqueue(bytes: ByteArray) {
            chunks += bytes
        }

        fun flat(): ByteArray = chunks.fold(ByteArray(0)) { acc, arr -> acc + arr }
    }

    @Test
    fun writeStringAppendsToScrollback() {
        val b = ComputerStdioBroadcaster(scrollbackBytes = 16)
        b.writeString("hi")
        val c = RecordingConsumer()
        b.addConsumer(c)
        assertContentEquals("hi".toByteArray(), c.flat())
    }

    @Test
    fun lateConsumerReceivesReplayThenNewWrites() {
        val b = ComputerStdioBroadcaster()
        b.writeString("old ")
        val c = RecordingConsumer()
        b.addConsumer(c)
        b.writeString("new")
        assertContentEquals("old new".toByteArray(), c.flat())
    }

    @Test
    fun removeConsumerStopsDelivery() {
        val b = ComputerStdioBroadcaster()
        val c = RecordingConsumer()
        b.addConsumer(c)
        b.writeString("a")
        b.removeConsumer(c)
        b.writeString("b")
        assertContentEquals("a".toByteArray(), c.flat())
    }

    @Test
    fun twoConsumersShareSameStream() {
        val b = ComputerStdioBroadcaster()
        val c1 = RecordingConsumer()
        val c2 = RecordingConsumer()
        b.addConsumer(c1)
        b.addConsumer(c2)
        b.writeString("shared")
        assertContentEquals("shared".toByteArray(), c1.flat())
        assertContentEquals("shared".toByteArray(), c2.flat())
    }

    @Test
    fun cursorTrackerFedByWriteString() {
        val b = ComputerStdioBroadcaster()
        b.writeString("Hi")
        assertEquals(2 to 0, b.cursor())
        b.writeString("\r\n")
        assertEquals(0 to 1, b.cursor())
    }

    @Test
    fun emptyWriteIsNoOp() {
        val b = ComputerStdioBroadcaster()
        val c = RecordingConsumer()
        b.addConsumer(c)
        b.writeString("")
        assertTrue(c.chunks.isEmpty())
    }
}
