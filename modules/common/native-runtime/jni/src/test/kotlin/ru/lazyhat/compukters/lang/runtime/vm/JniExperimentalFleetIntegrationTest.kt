/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            val decoded = ByteBuffer.wrap(event).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2, decoded.get().toInt())
            assertEquals(0L, decoded.long)
            assertEquals(0, decoded.int)
            assertEquals(0, JniExperimentalFleet.close(first))
            assertEquals(6, JniExperimentalFleet.stop(first, 0))
            assertEquals(0, JniExperimentalFleet.stop(second, 0))
            assertTrue(JniExperimentalFleet.poll(second, none, LongArray(0)) != 0)
        } finally {
            JniExperimentalFleet.close(first)
            JniExperimentalFleet.close(second)
        }
    }
}
