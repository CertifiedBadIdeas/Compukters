/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.file.Path
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FfmExperimentalFleetIntegrationTest {

    @Test
    fun `full native mailbox reports retryable contention`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        val bridge = FfmBridge.open(library)
        val fleet = bridge.openExperimentalFleet(ExperimentalFleetConfig(1, 1, 1, 128, 64, 64, 32))
        val session = VmSession.open(artifact, bridge)
        var machine: Long? = null
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (machine == null) {
            check(System.nanoTime() < deadline)
            try { machine = session.transferToFleet(fleet) } catch (_: VmFleetBusyException) { Thread.yield() }
        }
        var accepted = 0L
        while (true) {
            check(System.nanoTime() < deadline) { "mailbox did not fill" }
            try { fleet.resourceSnapshot(machine, accepted); accepted++ }
            catch (_: VmFleetBusyException) { if (accepted >= 3) break else Thread.yield() }
        }
        var sent = false
        var observed = false
        while (!observed) {
            check(System.nanoTime() < deadline) { "mailbox did not recover" }
            if (!sent) try { fleet.resourceSnapshot(machine, 10_000); sent = true } catch (_: VmFleetBusyException) { }
            val event = fleet.poll()
            if (event is ExperimentalFleetEvent.Resources && event.request == 10_000L) observed = true
            if (event == null) Thread.yield()
        }
        var stopping = false
        var stopped = false
        while (!stopped) {
            check(System.nanoTime() < deadline) { "fleet did not stop" }
            if (!stopping) try { fleet.stop(machine); stopping = true } catch (_: VmFleetBusyException) { }
            stopped = fleet.poll() is ExperimentalFleetEvent.Stopped
            if (!stopped) Thread.yield()
        }
        session.close()
        fleet.close()
        bridge.close()
    }


    @Test
    fun `closing an empty fleet releases an event waiter`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val bridge = FfmBridge.open(library)
        val fleet = bridge.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32))
        val generation = fleet.eventGeneration()
        val wait = java.util.concurrent.CompletableFuture.supplyAsync { fleet.waitEvents(generation, 5_000) }
        kotlin.test.assertFailsWith<java.util.concurrent.TimeoutException> { wait.get(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
        fleet.close()
        assertTrue(!wait.get(5, java.util.concurrent.TimeUnit.SECONDS))
        bridge.close()
    }

    @Test
    fun `event waiting wakes for work and does not block command submission`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        val bridge = FfmBridge.open(library)
        val fleet = bridge.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32))
        val session = VmSession.open(artifact, bridge)
        val machine = session.transferToFleet(fleet)
        val generation = fleet.eventGeneration()
        assertEquals(null, fleet.poll())
        val wait = java.util.concurrent.CompletableFuture.supplyAsync { fleet.waitEvents(generation, 5_000) }
        kotlin.test.assertFailsWith<java.util.concurrent.TimeoutException> { wait.get(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
        fleet.resourceSnapshot(machine, 42)
        assertTrue(wait.get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(42L, (fleet.poll() as ExperimentalFleetEvent.Resources).request)
        val nextGeneration = fleet.eventGeneration()
        assertTrue(!fleet.waitEvents(nextGeneration, 0))
        fleet.stop(machine)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (fleet.poll() !is ExperimentalFleetEvent.Stopped) {
            check(System.nanoTime() < deadline)
            Thread.yield()
        }
        session.close()
        fleet.close()
        bridge.close()
    }

    @Test
    fun `FFM redstone reaches the correct machines`() {
        fun fixture(name: String) = Path.of(requireNotNull(System.getProperty(name))).readBytes()
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        FfmBridge.open(library).use { bridge ->
            bridge.openExperimentalFleet(ExperimentalFleetConfig(2, 32, 32, 128, 64, 64, 32)).use { fleet ->
                val input = VmSession.open(fixture("compukters.fleet.redstone.input.fixture"), bridge)
                val output = VmSession.open(fixture("compukters.fleet.redstone.output.fixture"), bridge)
                val a = input.transferToFleet(fleet)
                val b = output.transferToFleet(fleet)
                val deadline = System.nanoTime() + 10_000_000_000L
                fun next(): ExperimentalFleetEvent {
                    while (System.nanoTime() < deadline) { fleet.poll()?.let { return it }; Thread.yield() }
                    error("timed out waiting for fleet event")
                }
                assertFailsWith<VmBridgeException> { fleet.redstoneInput(a, 1 shl 30) }
                fleet.redstoneInput(a, (1 shl 2) or (11 shl 14))
                fleet.confirmRedstoneOutput(b, 0x12345678)
                fleet.grant(a, 1, 512)
                fleet.grant(b, 1, 512)
                val outcomes = (0..1).associate { val event = next() as ExperimentalFleetEvent.Outcome; event.machine to event.outcome }
                assertEquals(VmOutcome.Halted(VmValue.I32(11)), outcomes[a])
                assertEquals(VmOutcome.Halted(VmValue.I32(0x12345678)), outcomes[b])
                fleet.stop(a)
                fleet.stop(b)
                var stopped = 0
                while (stopped < 2) if (next() is ExperimentalFleetEvent.Stopped) stopped++
                input.close()
                output.close()
            }
        }
    }

    @Test
    fun `FFM canonical input acknowledges completed line`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        FfmBridge.open(library).use { bridge ->
            bridge.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 1024, 64, 64, 32)).use { fleet ->
                val session = VmSession.open(artifact, bridge)
                val machine = session.transferToFleet(fleet)
                val deadline = System.nanoTime() + 10_000_000_000L
                fun next(): ExperimentalFleetEvent {
                    while (System.nanoTime() < deadline) { fleet.poll()?.let { return it }; Thread.yield() }
                    error("timed out waiting for fleet event")
                }
                fleet.grant(machine, 1, 512)
                assertEquals(VmOutcome.WaitingForTerminalEvent, (next() as ExperimentalFleetEvent.Outcome).outcome)
                fleet.canonicalLine(machine, "hello".toCharArray(), 7)
                assertEquals(ExperimentalFleetEvent.InputResult(machine, 7, ExperimentalFleetInputResult.Accepted), next())
                var outcome: VmOutcome? = null
                for (tick in 2L..300L) {
                    fleet.grant(machine, tick, 512)
                    Thread.sleep(1)
                    val event = fleet.poll()
                    if (event is ExperimentalFleetEvent.Outcome && event.outcome is VmOutcome.Halted) { outcome = event.outcome; break }
                    if (event is ExperimentalFleetEvent.Error) error(event.message)
                }
                assertEquals(VmOutcome.Halted(VmValue.I32("hello".hashCode())), outcome)
                fleet.stop(machine)
                while (next() !is ExperimentalFleetEvent.Stopped) { }
                session.close()
            }
        }
    }

    @Test
    fun `FFM terminal input and observations reach fleet machine`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        FfmBridge.open(library).use { bridge ->
            ExperimentalFfmFleet.open(bridge, 1, 32, 32, 1024, 64, 64, 32).use { fleet ->
                val session = VmSession.open(artifact, bridge)
                val machine = session.transferToFleet(fleet)
                val deadline = System.nanoTime() + 10_000_000_000L
                fun next(): ExperimentalFleetEvent {
                    while (System.nanoTime() < deadline) { fleet.poll()?.let { return it }; Thread.yield() }
                    error("timed out waiting for fleet event")
                }
                fleet.resourceSnapshot(machine, 11)
                assertEquals(11L, (next() as ExperimentalFleetEvent.Resources).request)
                fleet.filesystemGeneration(machine, 12)
                assertEquals(12L, (next() as ExperimentalFleetEvent.FilesystemGeneration).request)
                fleet.grant(machine, 1, 512)
                assertEquals(VmOutcome.WaitingForTerminalEvent, (next() as ExperimentalFleetEvent.Outcome).outcome)
                fleet.terminalText(machine, "hello", request = 13)
                assertEquals(ExperimentalFleetEvent.InputResult(machine, 13, ExperimentalFleetInputResult.Accepted), next())
                fleet.terminalKey(machine, TerminalKey.ENTER, TerminalKeyAction.PRESS, emptySet())
                var outcome: VmOutcome? = null
                for (tick in 2L..300L) {
                    fleet.grant(machine, tick, 512)
                    Thread.sleep(1)
                    when (val event = fleet.poll()) {
                        is ExperimentalFleetEvent.Outcome -> if (event.outcome is VmOutcome.Halted) { outcome = event.outcome; break }
                        is ExperimentalFleetEvent.Error -> error(event.message)
                        else -> Unit
                    }
                }
                assertEquals(VmOutcome.Halted(VmValue.I32("hello".hashCode())), outcome)
                fleet.terminalUpdate(machine, 14, Long.MAX_VALUE)
                assertEquals(14L, (next() as ExperimentalFleetEvent.Terminal).request)
                fleet.stop(machine)
                while (next() !is ExperimentalFleetEvent.Stopped) { }
                session.close()
            }
        }
    }

    @Test
    fun `FFM compiler completion installs artifact in stored filesystem`() {
        fun fixture(name: String) = Path.of(requireNotNull(System.getProperty(name))).readBytes()
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put("CPKTSYS\u0000".encodeToByteArray()).putShort(1).putShort(0).putInt(0).array()
        val image = header + MessageDigest.getInstance("SHA-256").digest(header)
        val root = Files.createTempDirectory("ffm-fleet-compiler-").toRealPath()
        try {
            FfmBridge.open(library).use { bridge ->
                WorldFileSystemStore.open(root, bridge).use { store ->
                    val id = ComputerId.fromLongs(101, 102)
                    VmSession.openInStore(fixture("compukters.fleet.compiler.source.fixture"), store, id, image).use { writer ->
                        var outcome: VmOutcome
                        do { outcome = writer.advance(128, 64, 64) } while (outcome == VmOutcome.SliceExhausted)
                        assertTrue(outcome is VmOutcome.Halted)
                    }
                    ExperimentalFfmFleet.open(bridge, 1, 32, 32, 128, 64, 64, 32).use { fleet ->
                        val session = VmSession.openInStore(fixture("compukters.fleet.compiler.fixture"), store, id, image)
                        val machine = session.transferToFleet(fleet)
                        val deadline = System.nanoTime() + 10_000_000_000L
                        fun next(): ExperimentalFleetEvent {
                            while (System.nanoTime() < deadline) { fleet.poll()?.let { return it }; Thread.yield() }
                            error("timed out waiting for fleet event")
                        }
                        fleet.grant(machine, 1, 1024)
                        val request = next() as ExperimentalFleetEvent.Compilation
                        assertEquals("fun main() = 42", request.request.sources.single().utf8Bytes().decodeToString())
                        val source = VmVirtualPath.of("/home/main.kt")
                        fleet.fileStat(machine, 21, source)
                        val stat = next() as ExperimentalFleetEvent.FileStat
                        fleet.fileList(machine, 22, VmVirtualPath.of("/home"))
                        assertTrue((next() as ExperimentalFleetEvent.FileList).listing.entries.any { it.name == "main.kt" })
                        fleet.fileRead(machine, 23, source, 0, 1024, stat.stat.metadata.generation)
                        assertEquals("fun main() = 42", (next() as ExperimentalFleetEvent.FileRead).chunk.bytes.decodeToString())
                        val artifact = fixture("compukters.shell.artifact")
                        val target = VmVirtualPath.of("/home/demo")
                        fleet.prepareDeployment(machine, 24, artifact)
                        val candidate = ((next() as ExperimentalFleetEvent.Deployment).result as ExperimentalFleetDeploymentResult.Prepared).candidate
                        fleet.deploymentRevision(machine, 25, target)
                        assertEquals(VmExecutableRevision.Absent, ((next() as ExperimentalFleetEvent.Deployment).result as ExperimentalFleetDeploymentResult.Revision).revision)
                        fleet.installDeployment(machine, 26, target, candidate, VmExecutableRevision.Absent)
                        assertTrue((next() as ExperimentalFleetEvent.Deployment).result is ExperimentalFleetDeploymentResult.Installed)
                        fleet.completeCompilationArtifact(machine, request.request.token, fixture("compukters.shell.artifact"))
                        assertEquals(VmOutcome.Halted(VmValue.I32(0)), (next() as ExperimentalFleetEvent.Outcome).outcome)
                        fleet.detach(machine)
                        assertEquals(ExperimentalFleetEvent.Detached(machine), next())
                        VmSession.restoreFromFleet(fleet, machine).use { recovered ->
                            assertTrue(recovered.fileStat(VmVirtualPath.of("/home/main")).metadata.executable)
                        }
                        session.close()
                    }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `FFM inspection charges host request before dispatch`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val capability = CapabilityIdentity("app", "entry", 1, 2)
        val schema = HostCapabilitySchema(capability, listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
        FfmBridge.open(library).use { bridge ->
            bridge.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32,
                inspection = ExperimentalRequestInspection(capability, 0, "request exceeds budget"))).use { fleet ->
                val session = VmSession.open(artifact, listOf(schema), bridge)
                val machine = session.transferToFleet(fleet)
                val deadline = System.nanoTime() + 10_000_000_000L
                fun next(): ExperimentalFleetEvent {
                    while (System.nanoTime() < deadline) { fleet.poll()?.let { return it }; Thread.yield() }
                    error("timed out waiting for fleet event")
                }
                fleet.grant(machine, 1, 256)
                val inspection = next() as ExperimentalFleetEvent.Inspection
                assertEquals(capability, inspection.request.capability)
                fleet.resolveCost(machine, inspection.request.identity, 1)
                val request = ((next() as ExperimentalFleetEvent.Outcome).outcome as VmOutcome.HostRequestBatch).requests.single()
                assertEquals(inspection.request.identity, request.identity)
                fleet.reply(machine, request.identity, HostResponse.UnitSuccess)
                assertEquals(VmOutcome.Halted(null), (next() as ExperimentalFleetEvent.Outcome).outcome)
                fleet.stop(machine)
                while (next() !is ExperimentalFleetEvent.Stopped) { }
                session.close()
            }
        }
    }

    @Test
    fun `FFM host reply resumes a guest under coordinated turn`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val schema = HostCapabilitySchema(CapabilityIdentity("app", "entry", 1, 2), listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
        FfmBridge.open(library).use { bridge ->
            ExperimentalFfmFleet.open(bridge, 1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true).use { fleet ->
                val session = VmSession.open(artifact, listOf(schema), bridge)
                val machine = session.transferToFleet(fleet)
                val deadline = System.nanoTime() + 10_000_000_000L
                fun next(): ExperimentalFleetEvent {
                    while (System.nanoTime() < deadline) { fleet.poll()?.let { return it }; Thread.yield() }
                    error("timed out waiting for fleet event")
                }
                fleet.grant(machine, 4, 256)
                val request = ((next() as ExperimentalFleetEvent.Outcome).outcome as VmOutcome.HostRequestBatch).requests.single()
                assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                fleet.reply(machine, request.identity, HostResponse.UnitSuccess)
                fleet.grant(machine, 4, 256)
                assertEquals(VmOutcome.Halted(null), (next() as ExperimentalFleetEvent.Outcome).outcome)
                assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                fleet.stop(machine)
                while (next() !is ExperimentalFleetEvent.Stopped) { }
                session.close()
            }
        }
    }

    @Test
    fun `FFM fleet recovery keeps store identity for checkpoint`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put("CPKTSYS\u0000".encodeToByteArray()).putShort(1).putShort(0).putInt(0).array()
        val image = header + MessageDigest.getInstance("SHA-256").digest(header)
        val root = Files.createTempDirectory("ffm-fleet-timer-").toRealPath()
        try {
            FfmBridge.open(library).use { bridge ->
                WorldFileSystemStore.open(root, bridge).use { store ->
                    ExperimentalFfmFleet.open(bridge, 1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true).use { fleet ->
                        val id = ComputerId.fromLongs(99, 100)
                        val initial = VmSession.openInStore(artifact, store, id, image)
                        val machine = initial.transferToFleet(fleet)
                        val deadline = System.nanoTime() + 10_000_000_000L
                        fun next(): ExperimentalFleetEvent {
                            while (System.nanoTime() < deadline) { fleet.poll()?.let { return it }; Thread.yield() }
                            error("timed out waiting for fleet event")
                        }
                        fleet.grant(machine, 1, 512)
                        assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                        fleet.detachWithTimers(machine, 2)
                        val timer = (next() as ExperimentalFleetEvent.Detached).timers.single()
                        val host = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(timer.identity.taskId).putLong(timer.identity.requestId).putLong(timer.scope)
                            .putInt(timer.duration).putLong(timer.remaining).array()
                        VmSession.restoreFromFleet(fleet, machine).use { it.checkpoint(store, id, host) }
                        val restored = requireNotNull(VmSession.restoreInStore(store, id, image))
                        val bytes = ByteBuffer.wrap(restored.hostStateBytes()).order(ByteOrder.LITTLE_ENDIAN)
                        val persisted = ExperimentalFleetTimer(VmHostRequestIdentity(bytes.int, bytes.long), bytes.long, bytes.int, bytes.long)
                        assertEquals(timer, persisted)
                        store.discardCheckpoint(id)
                        val restarted = restored.session.transferToFleet(fleet, 100, listOf(persisted))
                        fleet.grant(restarted, 100, 512)
                        assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                        fleet.grant(restarted, 101, 512)
                        assertTrue((next() as ExperimentalFleetEvent.Outcome).outcome is VmOutcome.Halted)
                        fleet.stop(restarted)
                        while (next() !is ExperimentalFleetEvent.Stopped) { }
                        restored.session.close()
                        initial.close()
                    }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `FFM transfers ownership and preserves timer across detach`() {
        val library = Path.of(requireNotNull(System.getProperty("compukter.ffi.library")))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        FfmBridge.open(library).use { bridge ->
            ExperimentalFfmFleet.open(bridge, 1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true).use { fleet ->
                assertFailsWith<IllegalStateException> { bridge.close() }
                val session = VmSession.open(artifact, bridge)
                val machine = session.transferToFleet(fleet)
                assertFailsWith<IllegalStateException> { session.advance(128, 64, 64) }
                val deadline = System.nanoTime() + 10_000_000_000L
                fun next(): ExperimentalFleetEvent {
                    while (System.nanoTime() < deadline) {
                        fleet.poll()?.let { return it }
                        Thread.yield()
                    }
                    error("timed out waiting for fleet event")
                }
                fleet.grant(machine, 1, 512)
                assertTrue((next() as ExperimentalFleetEvent.Quiescent).timersPending)
                fleet.detachWithTimers(machine, 2)
                val detached = next() as ExperimentalFleetEvent.Detached
                assertEquals(1L, detached.timers.single().remaining)
                val recovered = VmSession.restoreFromFleet(fleet, machine)
                val invalid = detached.timers.single().copy(duration = detached.timers.single().duration + 1)
                assertFailsWith<VmBridgeException> { recovered.transferToFleet(fleet, 100, listOf(invalid)) }
                val restarted = recovered.transferToFleet(fleet, 100, detached.timers)
                fleet.grant(restarted, 100, 512)
                assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                fleet.grant(restarted, 101, 512)
                assertTrue((next() as ExperimentalFleetEvent.Outcome).outcome is VmOutcome.Halted)
                fleet.stop(restarted)
                while (next() !is ExperimentalFleetEvent.Stopped) { }
                recovered.close()
                session.close()
            }
        }
    }
}
