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
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType

class JniExperimentalFleetIntegrationTest {
    @Test
    fun `tick retirement reports survive a host continuation over JNI`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val capability = CapabilityIdentity("app", "entry", 1, 2)
        val schema = HostCapabilitySchema(capability, listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
        ExperimentalJniFleet.open(library, 1, 32, 32, 128, 64, 64, 32, reportTurns = true).use { fleet ->
            val session = VmSession.open(artifact, listOf(schema), JniBridge.open(library))
            val machine = session.transferToFleet(fleet)
            val deadline = System.nanoTime() + 5_000_000_000L
            fun next(): ExperimentalFleetEvent {
                while (System.nanoTime() < deadline) {
                    fleet.poll()?.let { return it }
                    Thread.yield()
                }
                error("timed out waiting for fleet event")
            }
            try {
                fleet.grant(machine, 4, 256)
                val request = ((next() as ExperimentalFleetEvent.Outcome).outcome as VmOutcome.HostRequestBatch).requests.single()
                val before = next() as ExperimentalFleetEvent.Quiescent
                assertEquals(machine, before.machine)
                assertEquals(4L, before.tick)
                fleet.reply(machine, request.identity, HostResponse.UnitSuccess)
                assertEquals(VmOutcome.Halted(null), (next() as ExperimentalFleetEvent.Outcome).outcome)
                val after = next() as ExperimentalFleetEvent.Quiescent
                assertEquals(before.machine, after.machine)
                assertEquals(before.tick, after.tick)
                assertTrue(after.retired > before.retired)
                assertTrue(after.retired <= 256)
            } finally {
                fleet.stop(machine)
                while (next() !is ExperimentalFleetEvent.Stopped) { }
                session.close()
            }
        }
    }

    @Test
    fun `expired absolute deadline does not grant execution time`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val capability = CapabilityIdentity("app", "entry", 1, 2)
        val schema = HostCapabilitySchema(capability, listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
        ExperimentalJniFleet.open(library, 1, 32, 32, 128, 64, 64, 32).use { fleet ->
            val session = VmSession.open(artifact, listOf(schema), JniBridge.open(library))
            val machine = session.transferToFleet(fleet)
            try {
                fleet.grant(machine, 1, 256, System.nanoTime() - 1_000_000_000L)
                Thread.sleep(20)
                assertEquals(null, fleet.poll())
                fleet.grant(machine, 2, 256)
                val deadline = System.nanoTime() + 5_000_000_000L
                var event: ExperimentalFleetEvent?
                do {
                    event = fleet.poll()
                    if (event == null) Thread.yield()
                    assertTrue(System.nanoTime() < deadline)
                } while (event == null)
                assertEquals(2L, (event as ExperimentalFleetEvent.Outcome).tick)
                assertTrue(event.outcome is VmOutcome.HostRequestBatch)
            } finally {
                fleet.stop(machine)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (fleet.poll() !is ExperimentalFleetEvent.Stopped) {
                    assertTrue(System.nanoTime() < deadline)
                    Thread.yield()
                }
                session.close()
            }
        }
    }

    @Test
    fun `host inspection and response resume a real guest through JNI`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val capability = CapabilityIdentity("app", "entry", 1, 2)
        val schema = HostCapabilitySchema(capability, listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
        val fleet = ExperimentalJniFleet.open(library, 1, 32, 32, 128, 64, 64, 32, ExperimentalRequestInspection(capability, 0, "request exceeds budget"))
        val session = VmSession.open(artifact, listOf(schema), JniBridge.open(library))
        val machine = session.transferToFleet(fleet)
        val deadline = System.nanoTime() + 5_000_000_000L
        fun nextEvent(): ExperimentalFleetEvent {
            while (System.nanoTime() < deadline) {
                fleet.poll()?.let { return it }
                Thread.yield()
            }
            error("timed out waiting for fleet event")
        }
        var detached = false
        try {
            fleet.grant(machine, 1, 256)
            val inspection = nextEvent() as ExperimentalFleetEvent.Inspection
            assertEquals(machine, inspection.machine)
            assertEquals(capability, inspection.request.capability)
            assertEquals(0, inspection.request.operation)
            fleet.resolveCost(machine, inspection.request.identity, 1)
            val request = (nextEvent() as ExperimentalFleetEvent.Outcome).outcome as VmOutcome.HostRequestBatch
            assertEquals(inspection.request.identity, request.requests.single().identity)
            fleet.reply(machine, inspection.request.identity, HostResponse.UnitSuccess)
            assertEquals(VmOutcome.Halted(null), (nextEvent() as ExperimentalFleetEvent.Outcome).outcome)
            fleet.detach(machine)
            assertEquals(ExperimentalFleetEvent.Detached(machine), nextEvent())
            detached = true
            VmSession.restoreFromFleet(fleet, machine).close()
        } finally {
            if (!detached) {
                fleet.stop(machine)
                while (nextEvent() !is ExperimentalFleetEvent.Stopped) { }
            }
            session.close()
            fleet.close()
        }
    }
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
    fun `executed quiescent session transfers without losing its progress`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.shell.artifact"))).readBytes()
        val bridge = JniBridge.open(library)
        ExperimentalJniFleet.open(library, 1, 32, 32, 128, 64, 64, 16).use { fleet ->
            VmSession.open(artifact, bridge).use { session ->
                val progress = session.advanceWithRetirementLimit(128, 64, Int.MAX_VALUE, 1)
                assertTrue(progress.retiredInstructions > 0)
                val before = session.resourceSnapshot().executedInstructions
                val machine = session.transferToFleet(fleet)
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
                    assertEquals(before, restored.resourceSnapshot().executedInstructions)
                }
            }
        }
    }

    @Test
    fun `a second native library cannot supply handles to the fleet`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library"))).toAbsolutePath()
        JniBridge.open(library)
        val different = library.resolveSibling("different-native-library")
        kotlin.test.assertFailsWith<VmBridgeException> { JniBridge.open(different) }
    }

}
