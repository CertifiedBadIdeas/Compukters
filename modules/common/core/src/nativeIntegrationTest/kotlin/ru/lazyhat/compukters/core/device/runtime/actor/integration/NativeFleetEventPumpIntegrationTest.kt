/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor.integration

import ru.lazyhat.compukters.core.device.runtime.program.integration.installFfmRuntime
import ru.lazyhat.compukters.core.device.runtime.program.NativeProgramVmSessionFactory
import ru.lazyhat.compukters.core.device.runtime.program.ProgramFileSystemLaunchContext
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetEventPump
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeFrameCredit
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetConfig
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetTimer
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeFleetEventPumpIntegrationTest {
    @Test
    fun `filesystem session transfers recovers and checkpoints with its timer`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .put("CPKTSYS\u0000".encodeToByteArray()).putShort(1).putShort(0).putInt(0).array()
        val image = VmSystemImage.of(header + MessageDigest.getInstance("SHA-256").digest(header))
        val root = Files.createTempDirectory("fleet-session-integration-").toRealPath()
        try {
            WorldFileSystemStore.open(root).use { store ->
                val id = ComputerId.fromLongs(99, 100)
                val factory = NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image))
                VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                    fun next(): ExperimentalFleetEvent {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (System.nanoTime() < deadline) {
                            fleet.poll()?.let { return it }
                            Thread.yield()
                        }
                        error("timed out waiting for fleet event")
                    }
                    val session = factory.open(artifact)
                    val invalidTimer = ExperimentalFleetTimer(VmHostRequestIdentity(1, 1), 0, 1, 1)
                    assertFailsWith<VmBridgeException> { session.transferToFleet(fleet, 0, listOf(invalidTimer)) }
                    assertTrue(session.resourceSnapshot().executedInstructions >= 0)
                    val machine = session.transferToFleet(fleet, 0, emptyList())
                    assertFailsWith<IllegalStateException> { session.close() }
                    assertFailsWith<IllegalStateException> { session.recoverFromFleet(fleet, machine + 1) }
                    fleet.grant(machine, 1, 512)
                    assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                    fleet.detachWithTimers(machine, 2)
                    val detached = next() as ExperimentalFleetEvent.Detached
                    val timer = detached.timers.single()
                    assertEquals(1L, timer.remaining)
                    session.recoverFromFleet(fleet, machine)
                    val host = byteArrayOf(1, 2, 3)
                    session.checkpoint(host)
                    session.close()
                    val restored = requireNotNull(factory.restoreBoot())
                    assertContentEquals(host, restored.hostState)
                    factory.discardCheckpoint()
                    val restoredMachine = restored.session.transferToFleet(fleet, 100, listOf(timer))
                    fleet.grant(restoredMachine, 100, 512)
                    assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                    fleet.grant(restoredMachine, 101, 512)
                    assertTrue((next() as ExperimentalFleetEvent.Outcome).outcome is VmOutcome.Halted)
                    assertTrue(next() is ExperimentalFleetEvent.Quiescent)
                    fleet.stop(restoredMachine)
                    assertTrue(next() is ExperimentalFleetEvent.Stopped)
                    restored.session.acknowledgeFleetStop(fleet, restoredMachine)
                    restored.session.close()
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `native continuation charges only the newly retired instructions`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val schema = HostCapabilitySchema(CapabilityIdentity("app", "entry", 1, 2),
            listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)))
        val notifications = LinkedBlockingQueue<Unit>()
        val events = mutableListOf<ExperimentalFleetEvent>()
        val owner = Thread.currentThread()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val session = VmSession.open(artifact, listOf(schema))
            val machine = session.transferToFleet(fleet)
            val credit = ProgramRuntimeFrameCredit(4, 256, Long.MAX_VALUE, true)
            NativeFleetEventPump(fleet, { check(Thread.currentThread() === owner) }, { notifications.offer(Unit) }, events::add).use { pump ->
                fun await(predicate: (ExperimentalFleetEvent) -> Boolean): ExperimentalFleetEvent {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (true) {
                        pump.pump(32)
                        val index = events.indexOfFirst(predicate)
                        if (index >= 0) return events.removeAt(index)
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0) { "timed out awaiting fleet event" }
                        assertNotNull(notifications.poll(remaining, TimeUnit.NANOSECONDS))
                    }
                }
                fleet.grant(machine, 4, 256)
                val request = ((await { it is ExperimentalFleetEvent.Outcome } as ExperimentalFleetEvent.Outcome).outcome as VmOutcome.HostRequestBatch).requests.single()
                val first = await { it is ExperimentalFleetEvent.Quiescent } as ExperimentalFleetEvent.Quiescent
                val firstDelta = credit.finishCumulative(first.retired, 256)
                val remaining = credit.remaining
                fleet.reply(machine, request.identity, HostResponse.UnitSuccess)
                credit.inFlight = true
                fleet.grant(machine, 4, remaining.toInt())
                val halted = await { it is ExperimentalFleetEvent.Outcome } as ExperimentalFleetEvent.Outcome
                assertEquals(VmOutcome.Halted(null), halted.outcome)
                val second = await { it is ExperimentalFleetEvent.Quiescent } as ExperimentalFleetEvent.Quiescent
                val secondDelta = credit.finishCumulative(second.retired, remaining)
                assertEquals(second.retired, firstDelta + secondDelta)
                assertEquals(256 - second.retired, credit.remaining)
                fleet.stop(machine)
                await { it is ExperimentalFleetEvent.Stopped }
            }
            session.close()
        }
    }

    @Test
    fun `native events wake the owner and are delivered on the owner thread`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        val owner = Thread.currentThread()
        val notifications = LinkedBlockingQueue<Unit>()
        val events = mutableListOf<ExperimentalFleetEvent>()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32)).use { fleet ->
            val session = VmSession.open(artifact)
            val machine = session.transferToFleet(fleet)
            NativeFleetEventPump(fleet, { check(Thread.currentThread() === owner) }, { notifications.offer(Unit) }, events::add).use { pump ->
                fun await(predicate: (ExperimentalFleetEvent) -> Boolean) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (events.none(predicate)) {
                        pump.pump(32)
                        if (events.any(predicate)) break
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0) { "timed out awaiting fleet event" }
                        assertNotNull(notifications.poll(remaining, TimeUnit.NANOSECONDS))
                    }
                }
                fleet.resourceSnapshot(machine, 42)
                await { it is ExperimentalFleetEvent.Resources && it.request == 42L }
                assertEquals(1, events.count { it is ExperimentalFleetEvent.Resources && it.request == 42L })
                fleet.stop(machine)
                await { it is ExperimentalFleetEvent.Stopped && it.machine == machine }
            }
            session.close()
        }
    }
}
