package ru.lazyhat.compukterkraft.core.computer.vm.api

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
