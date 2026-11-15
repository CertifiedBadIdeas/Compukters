/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JniExperimentalFleetIntegrationTest {
    @Test
    fun `stop event survives a short output buffer and handles are isolated`() {
        JniBridge.open(Path.of(requireNotNull(System.getProperty("compukter.jni.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.shell.artifact"))).readBytes()
        val config = intArrayOf(1, 32, 32, 64, 64, 64, 16)
        val none = byteArrayOf()
        fun create(): Long {
            val handle = LongArray(1)
            assertEquals(0, JniExperimentalFleet.create(artifact, byteArrayOf(1, 0), 1, config, none, none, none, 0, 0, 0, handle))
            return handle[0]
        }
        val first = create()
        val second = create()
        try {
            assertEquals(0, JniExperimentalFleet.stop(first, 0))
            val written = LongArray(1)
            val deadline = System.nanoTime() + 5_000_000_000L
            var result: Int
            do {
                result = JniExperimentalFleet.poll(first, none, written)
                if (result == 0) Thread.yield()
            } while (result == 0 && System.nanoTime() < deadline)
            assertEquals(10, result)
            assertEquals(13L, written[0])
            val event = ByteArray(written[0].toInt())
            assertEquals(0, JniExperimentalFleet.poll(first, event, written))
            assertEquals(ExperimentalFleetEvent.Stopped(0, emptyList()), ExperimentalFleetEventWire.decode(event))
            assertEquals(0, JniExperimentalFleet.close(first))
            assertEquals(6, JniExperimentalFleet.stop(first, 0))
            assertEquals(6, JniExperimentalFleet.terminalKey(first, 0, 67, 0, 2))
            assertEquals(1, JniExperimentalFleet.terminalKey(first, 0, 999, 0, 2))
            assertEquals(6, JniExperimentalFleet.replyWire(first, 0, 1, 1, byteArrayOf(1, 0)))
            assertEquals(1, JniExperimentalFleet.replyWire(first, 0, 1, 1, byteArrayOf(1, 99)))
            assertEquals(6, JniExperimentalFleet.replyFailure(first, 0, 1, 1, 4, byteArrayOf(65)))
            assertEquals(1, JniExperimentalFleet.replyFailure(first, 0, 1, 1, 4, none))
            assertEquals(0, JniExperimentalFleet.stop(second, 0))
            assertTrue(JniExperimentalFleet.poll(second, none, LongArray(0)) != 0)
        } finally {
            JniExperimentalFleet.close(first)
            JniExperimentalFleet.close(second)
        }
    }
    @Test
    fun `terminal queue failure reaches JVM with its machine identity`() {
        JniBridge.open(Path.of(requireNotNull(System.getProperty("compukter.jni.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.shell.artifact"))).readBytes()
        val handle = LongArray(1)
        val none = byteArrayOf()
        assertEquals(0, JniExperimentalFleet.create(artifact, byteArrayOf(1, 0), 1, intArrayOf(1, 32, 32, 64, 64, 64, 16), none, none, none, 0, 0, 0, handle))
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            repeat(257) {
                var status: Int
                do {
                    status = JniExperimentalFleet.terminalKey(handle[0], 0, 13, 0, 0)
                    if (status == 7) Thread.yield()
                    assertTrue(System.nanoTime() < deadline, "terminal queue submission timed out")
                } while (status == 7)
                assertEquals(0, status)
            }
            val output = ByteArray(1024)
            val written = LongArray(1)
            do {
                assertEquals(0, JniExperimentalFleet.poll(handle[0], output, written))
                if (written[0] == 0L) Thread.yield()
                assertTrue(System.nanoTime() < deadline, "fleet error timed out")
            } while (written[0] == 0L)
            assertEquals(ExperimentalFleetEvent.Error(0, "terminal key: QueueFull"), ExperimentalFleetEventWire.decode(output.copyOf(written[0].toInt())))
        } finally {
            JniExperimentalFleet.close(handle[0])
        }
    }

}
