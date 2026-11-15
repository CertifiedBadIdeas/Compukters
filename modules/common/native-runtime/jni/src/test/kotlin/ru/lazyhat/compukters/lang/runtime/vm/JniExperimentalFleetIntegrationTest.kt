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

    @Test
    fun `existing JNI session transfers to fleet and back without two owners`() {
        val bridge = JniBridge.open(Path.of(requireNotNull(System.getProperty("compukter.jni.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.shell.artifact"))).readBytes()
        val created = bridge.create(artifact, byteArrayOf(1, 0))
        assertEquals(0, created[0].toInt())
        val original = ByteBuffer.wrap(created, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val fleet = LongArray(1)
        val none = byteArrayOf()
        assertEquals(0, JniExperimentalFleet.create(none, byteArrayOf(1, 0), 0, intArrayOf(1, 32, 32, 64, 64, 64, 16), none, none, none, 0, 0, 0, fleet))
        var restored = 0L
        try {
            val machine = LongArray(1)
            assertEquals(0, JniExperimentalFleet.attachSession(fleet[0], original, machine))
            assertEquals(6, JniNative.close(original))
            assertEquals(0, JniExperimentalFleet.detach(fleet[0], machine[0]))
            val deadline = System.nanoTime() + 5_000_000_000L
            val bytes = ByteArray(64)
            val written = LongArray(1)
            do {
                assertEquals(0, JniExperimentalFleet.poll(fleet[0], bytes, written))
                if (written[0] == 0L) Thread.yield()
                assertTrue(System.nanoTime() < deadline)
            } while (written[0] == 0L)
            assertEquals(ExperimentalFleetEvent.Detached(machine[0]), ExperimentalFleetEventWire.decode(bytes.copyOf(written[0].toInt())))
            val result = LongArray(1)
            assertEquals(0, JniExperimentalFleet.restoreSession(fleet[0], machine[0], result))
            restored = result[0]
            assertTrue(restored != original)
            assertEquals(1, JniExperimentalFleet.restoreSession(fleet[0], machine[0], LongArray(1)))
            val output = ByteArray(JniNative.maximumOutcomeBytes().toInt())
            val advanceWritten = LongArray(1)
            assertEquals(0, JniNative.advanceWithRetirementLimit(restored, 128, 64, Int.MAX_VALUE, 0, output, advanceWritten, LongArray(1)))
            val controlBytes = bridge.create(artifact, byteArrayOf(1, 0))
            val control = ByteBuffer.wrap(controlBytes, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long
            try {
                val controlOutput = ByteArray(output.size)
                val controlWritten = LongArray(1)
                val expected = JniNative.advance(control, 128, 64, Int.MAX_VALUE, controlOutput, controlWritten)
                val actual = JniNative.advance(restored, 128, 64, Int.MAX_VALUE, output, advanceWritten)
                assertEquals(expected, actual)
                if (actual == 0) {
                    assertEquals(controlWritten[0], advanceWritten[0])
                    kotlin.test.assertContentEquals(controlOutput.copyOf(controlWritten[0].toInt()), output.copyOf(advanceWritten[0].toInt()))
                }
            } finally {
                bridge.close(control)
            }
        } finally {
            if (restored != 0L) bridge.close(restored)
            JniExperimentalFleet.close(fleet[0])
        }
    }

    @Test
    fun `typed session moves into fleet and returns as a usable session`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.shell.artifact"))).readBytes()
        val bridge = JniBridge.open(library)
        ExperimentalJniFleet.open(
            library, 1, 32, 32, 128, 64, 64, 16,
            ExperimentalRequestInspection(CapabilityIdentity("app", "entry", 1, 0), 0, "request exceeds budget"),
        ).use { fleet ->
            val original = VmSession.open(artifact, bridge)
            val machine = original.transferToFleet(fleet)
            kotlin.test.assertFailsWith<VmBridgeException> { fleet.close() }
            kotlin.test.assertFailsWith<IllegalStateException> { original.advance(128, 64, 64) }
            original.close()
            fleet.detach(machine)
            val deadline = System.nanoTime() + 5_000_000_000L
            var event: ExperimentalFleetEvent?
            do {
                event = fleet.poll()
                if (event == null) Thread.yield()
                assertTrue(System.nanoTime() < deadline)
            } while (event == null)
            assertEquals(ExperimentalFleetEvent.Detached(machine), event)
            VmSession.restoreFromFleet(fleet, machine).use { restored ->
                assertEquals(VmOutcome.SliceExhausted, restored.advanceWithRetirementLimit(128, 64, Int.MAX_VALUE, 0).outcome)
            }
        }
    }

    @Test
    fun `refused transfer leaves the original session usable`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.shell.artifact"))).readBytes()
        val bridge = JniBridge.open(library)
        ExperimentalJniFleet.open(library, 1, 32, 32, 128, 64, 64, 16).use { fleet ->
            VmSession.open(artifact, bridge).use { session ->
                val progress = session.advanceWithRetirementLimit(128, 64, Int.MAX_VALUE, 1)
                assertTrue(progress.retiredInstructions > 0)
                kotlin.test.assertFailsWith<VmBridgeException> { session.transferToFleet(fleet) }
                assertEquals(VmOutcome.SliceExhausted, session.advanceWithRetirementLimit(128, 64, Int.MAX_VALUE, 0).outcome)
            }
        }
    }

}
