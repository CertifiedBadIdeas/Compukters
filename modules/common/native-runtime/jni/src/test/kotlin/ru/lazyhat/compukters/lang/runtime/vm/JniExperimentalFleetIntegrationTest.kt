/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadFailure

class JniExperimentalFleetIntegrationTest {
    @Test
    fun `deployment candidates are isolated and survive a revision conflict over JNI`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val bridge = JniBridge.open(library)
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put("CPKTSYS\u0000".encodeToByteArray()).putShort(1).putShort(0).putInt(0).array()
        val image = header + MessageDigest.getInstance("SHA-256").digest(header)
        val root = Files.createTempDirectory("fleet-deployment-").toRealPath()
        try {
            WorldFileSystemStore.open(root, bridge).use { store ->
                ExperimentalJniFleet.open(library, 2, 32, 32, 128, 64, 64, 32).use { fleet ->
                    val first = VmSession.openInStore(artifact, store, ComputerId.fromLongs(95, 96), image)
                    val second = VmSession.openInStore(artifact, store, ComputerId.fromLongs(97, 98), image)
                    val a = first.transferToFleet(fleet)
                    val b = second.transferToFleet(fleet)
                    val deadline = System.nanoTime() + 10_000_000_000L
                    fun next(): ExperimentalFleetEvent {
                        while (System.nanoTime() < deadline) {
                            fleet.poll()?.let { return it }
                            Thread.yield()
                        }
                        error("timed out waiting for fleet event")
                    }
                    val path = VmVirtualPath.of("/home/demo")
                    fleet.prepareDeployment(a, 1, artifact)
                    val candidateA = ((next() as ExperimentalFleetEvent.Deployment).result as ExperimentalFleetDeploymentResult.Prepared).candidate
                    fleet.prepareDeployment(b, 2, artifact)
                    val candidateB = ((next() as ExperimentalFleetEvent.Deployment).result as ExperimentalFleetDeploymentResult.Prepared).candidate
                    kotlin.test.assertNotEquals(candidateA, candidateB)
                    fleet.installDeployment(b, 3, path, candidateA, VmExecutableRevision.Absent)
                    assertEquals(ExperimentalFleetEvent.Deployment(b, 3, ExperimentalFleetDeploymentResult.Failed(ExperimentalFleetDeploymentFailure.INVALID_CANDIDATE)), next())
                    fleet.installDeployment(a, 4, path, candidateA, VmExecutableRevision.Absent)
                    assertTrue((next() as ExperimentalFleetEvent.Deployment).result is ExperimentalFleetDeploymentResult.Installed)
                    fleet.prepareDeployment(a, 5, artifact)
                    val retry = ((next() as ExperimentalFleetEvent.Deployment).result as ExperimentalFleetDeploymentResult.Prepared).candidate
                    fleet.installDeployment(a, 6, path, retry, VmExecutableRevision.Absent)
                    assertEquals(ExperimentalFleetEvent.Deployment(a, 6, ExperimentalFleetDeploymentResult.Failed(ExperimentalFleetDeploymentFailure.BUSY)), next())
                    fleet.deploymentRevision(a, 7, path)
                    val revision = ((next() as ExperimentalFleetEvent.Deployment).result as ExperimentalFleetDeploymentResult.Revision).revision
                    fleet.installDeployment(a, 8, path, retry, revision)
                    assertTrue((next() as ExperimentalFleetEvent.Deployment).result is ExperimentalFleetDeploymentResult.Installed)
                    fleet.discardDeployment(b, 9, candidateB)
                    assertEquals(ExperimentalFleetEvent.Deployment(b, 9, ExperimentalFleetDeploymentResult.Discarded), next())
                    fleet.stop(a)
                    fleet.stop(b)
                    var stopped = 0
                    while (stopped < 2) if (next() is ExperimentalFleetEvent.Stopped) stopped++
                    first.close()
                    second.close()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `timer survives checkpoint and resumes on the restored tick`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val bridge = JniBridge.open(library)
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put("CPKTSYS\u0000".encodeToByteArray()).putShort(1).putShort(0).putInt(0).array()
        val image = header + MessageDigest.getInstance("SHA-256").digest(header)
        val root = Files.createTempDirectory("fleet-timer-").toRealPath()
        try {
            WorldFileSystemStore.open(root, bridge).use { store ->
                val id = ComputerId.fromLongs(93, 94)
                ExperimentalJniFleet.open(library, 1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true).use { fleet ->
                    val deadline = System.nanoTime() + 10_000_000_000L
                    fun next(): ExperimentalFleetEvent {
                        while (System.nanoTime() < deadline) {
                            fleet.poll()?.let { return it }
                            Thread.yield()
                        }
                        error("timed out waiting for fleet event")
                    }
                    val session = VmSession.openInStore(artifact, store, id, image)
                    val machine = session.transferToFleet(fleet)
                    fleet.grant(machine, 1, 512)
                    assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                    fleet.detachWithTimers(machine, 2)
                    val detached = next() as ExperimentalFleetEvent.Detached
                    assertEquals(1, detached.timers.size)
                    assertEquals(1L, detached.timers.single().remaining)
                    val timer = detached.timers.single()
                    val host = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(timer.identity.taskId).putLong(timer.identity.requestId).putLong(timer.scope)
                        .putInt(timer.duration).putLong(timer.remaining).array()
                    VmSession.restoreFromFleet(fleet, machine).use { it.checkpoint(store, id, host) }
                    val restored = requireNotNull(VmSession.restoreInStore(store, id, image))
                    val encoded = ByteBuffer.wrap(restored.hostStateBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    val persisted = ExperimentalFleetTimer(VmHostRequestIdentity(encoded.int, encoded.long), encoded.long, encoded.int, encoded.long)
                    assertEquals(timer, persisted)
                    store.discardCheckpoint(id)
                    val restoredMachine = restored.session.transferToFleet(fleet, 100, listOf(persisted))
                    fleet.grant(restoredMachine, 100, 512)
                    assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                    fleet.grant(restoredMachine, 101, 512)
                    val outcome = next() as ExperimentalFleetEvent.Outcome
                    assertTrue(outcome.outcome is VmOutcome.Halted)
                    fleet.stop(restoredMachine)
                    while (next() !is ExperimentalFleetEvent.Stopped) { }
                    session.close()
                    restored.session.close()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `redstone input and confirmed output reach the correct machine over JNI`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val bridge = JniBridge.open(library)
        fun fixture(name: String) = Path.of(requireNotNull(System.getProperty(name))).readBytes()
        ExperimentalJniFleet.open(library, 2, 32, 32, 128, 64, 64, 32).use { fleet ->
            val input = VmSession.open(fixture("compukters.fleet.redstone.input.fixture"), bridge)
            val output = VmSession.open(fixture("compukters.fleet.redstone.output.fixture"), bridge)
            val inputId = input.transferToFleet(fleet)
            val outputId = output.transferToFleet(fleet)
            val deadline = System.nanoTime() + 5_000_000_000L
            fun next(): ExperimentalFleetEvent {
                while (System.nanoTime() < deadline) {
                    fleet.poll()?.let { return it }
                    Thread.yield()
                }
                error("timed out waiting for fleet event")
            }
            try {
                kotlin.test.assertFailsWith<VmBridgeException> { fleet.redstoneInput(inputId, 1 shl 30) }
                fleet.redstoneInput(inputId, (1 shl 2) or (11 shl 14))
                fleet.confirmRedstoneOutput(outputId, 0x12345678)
                fleet.grant(inputId, 1, 512)
                fleet.grant(outputId, 1, 512)
                val outcomes = (0..1).associate {
                    val event = next() as ExperimentalFleetEvent.Outcome
                    event.machine to event.outcome
                }
                assertEquals(VmOutcome.Halted(VmValue.I32(11)), outcomes[inputId])
                assertEquals(VmOutcome.Halted(VmValue.I32(0x12345678)), outcomes[outputId])
            } finally {
                fleet.stop(inputId)
                fleet.stop(outputId)
                var stopped = 0
                while (stopped < 2) if (next() is ExperimentalFleetEvent.Stopped) stopped++
                input.close()
                output.close()
            }
        }
    }

    @Test
    fun `text and complete line reach waiting guest through JNI`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        val bridge = JniBridge.open(library)
        ExperimentalJniFleet.open(library, 1, 32, 32, 1024, 64, 64, 32).use { fleet ->
            for (canonical in listOf(false, true)) {
                val session = VmSession.open(artifact, bridge)
                if (canonical) {
                    var outcome: VmOutcome
                    do { outcome = session.advance(1024, 64, 64) } while (outcome == VmOutcome.SliceExhausted)
                    assertEquals(VmOutcome.WaitingForTerminalEvent, outcome)
                }
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
                    if (!canonical) {
                        fleet.canonicalLine(machine, charArrayOf(), request = 80)
                        assertEquals(ExperimentalFleetEvent.InputResult(machine, 80, ExperimentalFleetInputResult.CanonicalRejected(VmCanonicalLineFailure.NO_PENDING_READ)), next())
                    }
                    fleet.grant(machine, 1, 512)
                    assertEquals(VmOutcome.WaitingForTerminalEvent, (next() as ExperimentalFleetEvent.Outcome).outcome)
                    if (canonical) {
                        fleet.canonicalLine(machine, "hello".toCharArray(), request = 81)
                        assertEquals(ExperimentalFleetEvent.InputResult(machine, 81, ExperimentalFleetInputResult.Accepted), next())
                    } else {
                        fleet.terminalText(machine, "hello")
                        fleet.terminalKey(machine, TerminalKey.ENTER, TerminalKeyAction.PRESS, emptySet())
                    }
                    var outcome: VmOutcome? = null
                    for (tick in 2L..300L) {
                        fleet.grant(machine, tick, 512)
                        Thread.sleep(1)
                        val event = fleet.poll()
                        if (event is ExperimentalFleetEvent.Outcome && event.outcome is VmOutcome.Halted) {
                            outcome = event.outcome
                            break
                        }
                        if (event is ExperimentalFleetEvent.Error) error(event.message)
                    }
                    assertEquals(VmOutcome.Halted(VmValue.I32("hello".hashCode())), outcome)
                    fleet.terminalUpdate(machine, 11, Long.MAX_VALUE)
                    val full = next() as ExperimentalFleetEvent.Terminal
                    assertEquals(machine, full.machine)
                    assertEquals(11L, full.request)
                    val state = (full.update as TerminalUpdate.Full).state
                    assertEquals("hello", state.cells.take(5).map { it.codePoint.toChar() }.joinToString(""))
                    fleet.terminalUpdate(machine, 12, state.revision)
                    assertEquals(ExperimentalFleetEvent.Terminal(machine, 12, TerminalUpdate.Unchanged(state.revision)), next())
                } finally {
                    fleet.stop(machine)
                    while (next() !is ExperimentalFleetEvent.Stopped) { }
                    session.close()
                }
            }
        }
    }

    @Test
    fun `compilation request and result cross JNI and install into stored filesystem`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        fun fixture(name: String) = Path.of(requireNotNull(System.getProperty(name))).readBytes()
        val sourceWriter = fixture("compukters.fleet.compiler.source.fixture")
        val compiler = fixture("compukters.fleet.compiler.fixture")
        val resultArtifact = fixture("compukters.shell.artifact")
        val bridge = JniBridge.open(library)
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put("CPKTSYS\u0000".encodeToByteArray()).putShort(1).putShort(0).putInt(0).array()
        val image = header + MessageDigest.getInstance("SHA-256").digest(header)
        val root = Files.createTempDirectory("fleet-compiler-").toRealPath()
        try {
            WorldFileSystemStore.open(root, bridge).use { store ->
                val id = ComputerId.fromLongs(91, 92)
                VmSession.openInStore(sourceWriter, store, id, image).use { writer ->
                    var result: VmOutcome
                    do { result = writer.advance(128, 64, 64) } while (result == VmOutcome.SliceExhausted)
                    assertTrue(result is VmOutcome.Halted)
                }
                ExperimentalJniFleet.open(library, 1, 32, 32, 128, 64, 64, 32).use { fleet ->
                    val session = VmSession.openInStore(compiler, store, id, image)
                    val machine = session.transferToFleet(fleet)
                    val deadline = System.nanoTime() + 5_000_000_000L
                    fun next(): ExperimentalFleetEvent {
                        while (System.nanoTime() < deadline) {
                            fleet.poll()?.let { return it }
                            Thread.yield()
                        }
                        error("timed out waiting for fleet event")
                    }
                    var detached = false
                    try {
                        fleet.grant(machine, 1, 1024)
                        val request = next() as ExperimentalFleetEvent.Compilation
                        assertEquals(machine, request.machine)
                        assertEquals("/home/main.kt", request.request.sources.single().path)
                        assertEquals("fun main() = 42", request.request.sources.single().utf8Bytes().decodeToString())
                        val path = VmVirtualPath.of("/home/main.kt")
                        fleet.fileStat(machine, 31, path)
                        val stat = next() as ExperimentalFleetEvent.FileStat
                        assertEquals(machine, stat.machine)
                        assertEquals(31L, stat.request)
                        fleet.fileList(machine, 32, VmVirtualPath.of("/home"))
                        val listing = next() as ExperimentalFleetEvent.FileList
                        assertTrue(listing.listing.entries.any { it.name == "main.kt" })
                        fleet.fileRead(machine, 33, path, 0, 1024, stat.stat.metadata.generation)
                        val chunk = next() as ExperimentalFleetEvent.FileRead
                        assertEquals("fun main() = 42", chunk.chunk.bytes.decodeToString())
                        fleet.fileRead(machine, 34, path, 0, 1024, stat.stat.metadata.generation + 1)
                        assertEquals(ExperimentalFleetEvent.FileFailure(machine, 34, VmFileSystemReadFailure.STALE_GENERATION), next())
                        fleet.fileStat(machine, 35, VmVirtualPath.of("/home/missing"))
                        assertEquals(ExperimentalFleetEvent.FileFailure(machine, 35, VmFileSystemReadFailure.NOT_FOUND), next())
                        fleet.completeCompilationArtifact(machine, request.request.token, resultArtifact)
                        assertEquals(VmOutcome.Halted(VmValue.I32(0)), (next() as ExperimentalFleetEvent.Outcome).outcome)
                        fleet.detach(machine)
                        assertEquals(ExperimentalFleetEvent.Detached(machine), next())
                        detached = true
                        VmSession.restoreFromFleet(fleet, machine).use { restored ->
                            assertTrue(restored.fileStat(VmVirtualPath.of("/home/main")).metadata.executable)
                        }
                    } finally {
                        if (!detached) {
                            fleet.stop(machine)
                            while (next() !is ExperimentalFleetEvent.Stopped) { }
                        }
                        session.close()
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `tick retirement reports survive a host continuation over JNI`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.jni.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val capability = CapabilityIdentity("app", "entry", 1, 2)
        val schema = HostCapabilitySchema(capability, listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
        ExperimentalJniFleet.open(library, 1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true).use { fleet ->
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
                fleet.terminalUpdate(machine, 7, Long.MAX_VALUE)
                assertTrue(next() is ExperimentalFleetEvent.Terminal)
                fleet.grant(machine, 4, 256)
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
            assertEquals(6, JniExperimentalFleet.terminalKey(first, 0, 67, 0, 2, -1))
            assertEquals(1, JniExperimentalFleet.terminalKey(first, 0, 999, 0, 2, -1))
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
                    status = JniExperimentalFleet.terminalKey(handle[0], 0, 13, 0, 0, -1)
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
            val resources = original.resourceSnapshot()
            val generation = original.filesystemGeneration()
            val machine = original.transferToFleet(fleet)
            kotlin.test.assertFailsWith<VmBridgeException> { fleet.close() }
            kotlin.test.assertFailsWith<IllegalStateException> { original.advance(128, 64, 64) }
            original.close()
            fleet.resourceSnapshot(machine, 21)
            fleet.filesystemGeneration(machine, 22)
            val observationDeadline = System.nanoTime() + 5_000_000_000L
            fun observation(): ExperimentalFleetEvent {
                while (System.nanoTime() < observationDeadline) {
                    fleet.poll()?.let { return it }
                    Thread.yield()
                }
                error("timed out waiting for observation")
            }
            assertEquals(ExperimentalFleetEvent.Resources(machine, 21, resources), observation())
            assertEquals(ExperimentalFleetEvent.FilesystemGeneration(machine, 22, generation), observation())
            fleet.detach(machine)
            kotlin.test.assertFailsWith<VmBridgeException> { fleet.resourceSnapshot(machine, 23) }
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
