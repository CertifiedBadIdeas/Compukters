/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor.integration

import ru.lazyhat.compukters.api.addon.ProgramAddonAction
import ru.lazyhat.compukters.api.addon.ProgramAddonCompletion
import ru.lazyhat.compukters.core.device.runtime.actor.ActorRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetActorRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetDispatcher
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetEventPump
import ru.lazyhat.compukters.core.device.runtime.actor.NativeFleetOutbox
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorEffect
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorFailure
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeFrameCredit
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeTurnObserver
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSubmission
import ru.lazyhat.compukters.core.device.runtime.program.NativeProgramVmSessionFactory
import ru.lazyhat.compukters.core.device.runtime.program.ProgramFileSystemLaunchContext
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.integration.installFfmRuntime
import ru.lazyhat.compukters.lang.runtime.capability.HostCapabilitySchema
import ru.lazyhat.compukters.lang.runtime.capability.HostOperationSchema
import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.capability.HostValueType
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.VmFileSystemReadFailure
import ru.lazyhat.compukters.lang.runtime.fs.VmSystemImage
import ru.lazyhat.compukters.lang.runtime.fs.VmVirtualPath
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetConfig
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetTimer
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmCanonicalLineFailure
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeFleetEventPumpIntegrationTest {
    @Test
    fun `actor stop drains accepted commands and closes without owner pumping while ordinary output is full`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val host = ProgramRuntimeHost(NativeProgramVmSessionFactory())
        assertEquals(ProgramStartResult.Started, host.start(artifact))
        val owner = Thread.currentThread()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 1, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val machine = host.transferToFleet(fleet, 0)
            VmSession.open(artifact).use { observer ->
                val other = observer.transferToFleet(fleet)
                val generation = fleet.eventGeneration()
                fleet.resourceSnapshot(other, 42)
                assertTrue(fleet.waitEvents(generation, 5_000))
                val endpoint = VmActorEndpoint(ComputerId.fromLongs(41, 42), 1)
                var canceled = false
                NativeFleetActorRuntimeService(
                    fleet,
                    2,
                    1,
                    1,
                    { check(Thread.currentThread() === owner) },
                    {},
                    { _, _ -> error("ordinary output must not be pumped") },
                    onActorClosing = { address ->
                        check(Thread.currentThread() === owner)
                        assertEquals(endpoint, address)
                        canceled = true
                    },
                ).use { service ->
                    assertTrue(service.register(endpoint, machine, host))
                    val query = service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
                    // No pump at all: even the accepted query has not reached Rust yet.
                    val stopped = checkNotNull(service.stop(endpoint))
                    assertTrue(canceled && query.isCompletedExceptionally)
                    assertEquals(0L, stopped.get(5, TimeUnit.SECONDS))
                    assertEquals(ProgramRuntimeState.Closed, host.state)
                    val preserved = assertIs<ExperimentalFleetEvent.Resources>(fleet.poll())
                    assertEquals(other, preserved.machine)
                    assertEquals(42L, preserved.request)
                    fleet.stop(other, independent = true)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (true) {
                        check(System.nanoTime() < deadline)
                        val event = fleet.pollLifecycle(other)
                        if (event is ExperimentalFleetEvent.Stopped) break
                        Thread.yield()
                    }
                }
            }
        }
    }

    @Test
    fun `host releases ownership only after native stop and retains filesystem generation`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val host = ProgramRuntimeHost(NativeProgramVmSessionFactory())
        assertEquals(ProgramStartResult.Started, host.start(artifact))
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32)).use { fleet ->
            val machine = host.transferToFleet(fleet, 0)

            fun next(): ExperimentalFleetEvent {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < deadline) {
                    fleet.poll()?.let { return it }
                    Thread.yield()
                }
                error("timed out waiting for fleet event")
            }
            val published = fleet.eventGeneration()
            fleet.resourceSnapshot(machine, 1)
            assertTrue(fleet.waitEvents(published, 5_000))
            assertFailsWith<IllegalStateException> {
                host.acknowledgeFleetStop(
                    fleet,
                    ExperimentalFleetEvent.Stopped(machine + 1, emptyList(), 0),
                    ProgramRuntimeState.Idle,
                )
            }
            assertFailsWith<IllegalStateException> { host.close() }
            fleet.stop(machine, independent = true)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var stopped: ExperimentalFleetEvent.Stopped? = null
            while (stopped == null) {
                check(System.nanoTime() < deadline)
                stopped = fleet.pollLifecycle(machine) as? ExperimentalFleetEvent.Stopped
                if (stopped == null) Thread.yield()
            }
            host.acknowledgeFleetStop(fleet, stopped, ProgramRuntimeState.Idle)
            assertEquals(ProgramRuntimeState.Idle, host.state)
            assertEquals(stopped.filesystemGeneration, host.lastClosedFileSystemGeneration)
            val older = next() as ExperimentalFleetEvent.Resources
            assertEquals(machine, older.machine)
            assertEquals(1L, older.request)
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            host.close()
        }
    }

    @Test
    fun `live host transfers its timer to rust and resumes it after recovery`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val actions = mutableListOf<ProgramAddonAction>()
        val host =
            ProgramRuntimeHost(NativeProgramVmSessionFactory(), addonLifecyclePort = {
                actions.add(it)
                true
            })
        assertEquals(ProgramStartResult.Started, host.start(artifact))
        assertEquals(ProgramRuntimeState.Running, host.serverTick(1))
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val machine = host.transferToFleet(fleet, 1)
            assertFailsWith<IllegalStateException> { host.observeFleetLifecycle(fleet, machine + 1, VmOutcome.ProcessEntered(1)) }
            host.observeFleetLifecycle(fleet, machine, VmOutcome.ProcessEntered(1))
            host.observeFleetLifecycle(fleet, machine, VmOutcome.ProcessExited(1))
            assertEquals(listOf(ProgramAddonAction.Started(1), ProgramAddonAction.Stopped(1)), actions)
            assertFailsWith<IllegalStateException> { host.serverTick(2) }
            assertFailsWith<IllegalStateException> { host.shutdown() }
            fleet.detachWithTimers(machine, 2)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var detached: ExperimentalFleetEvent.Detached? = null
            while (detached == null) {
                check(System.nanoTime() < deadline)
                detached = fleet.poll() as? ExperimentalFleetEvent.Detached
                if (detached == null) Thread.yield()
            }
            assertEquals(1L, detached.timers.single().remaining)
            host.recoverFromFleet(fleet, machine, 2, detached.timers)
            assertEquals(ProgramRuntimeState.Running, host.serverTick(2))
            assertTrue(host.serverTick(3) is ProgramRuntimeState.Halted)
        }
        host.close()
    }

    @Test
    fun `filesystem session transfers recovers and checkpoints with its timer`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val header =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTSYS\u0000".encodeToByteArray())
                .putShort(1)
                .putShort(0)
                .putInt(0)
                .array()
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
                    assertTrue((next() as ExperimentalFleetEvent.Quiescent).timersPending)
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
                    assertTrue(!(next() as ExperimentalFleetEvent.Quiescent).timersPending)
                    fleet.stop(restoredMachine)
                    assertTrue(next() is ExperimentalFleetEvent.Stopped)
                    restored.session.acknowledgeFleetStop(fleet, restoredMachine)
                    restored.session.close()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native continuation charges only the newly retired instructions`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val schema =
            HostCapabilitySchema(
                CapabilityIdentity("app", "entry", 1, 2),
                listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)),
            )
        val notifications = LinkedBlockingQueue<Unit>()
        val events = mutableListOf<ExperimentalFleetEvent>()
        val owner = Thread.currentThread()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val session = VmSession.open(artifact, listOf(schema))
            val machine = session.transferToFleet(fleet)
            val credit = ProgramRuntimeFrameCredit(4, 256, Long.MAX_VALUE, true)
            NativeFleetDispatcher(
                fleet,
                8,
                8,
                { check(Thread.currentThread() === owner) },
                { notifications.offer(Unit) },
                events::add,
            ).use { pump ->
                fun await(predicate: (ExperimentalFleetEvent) -> Boolean): ExperimentalFleetEvent {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (true) {
                        pump.pumpLifecycle(32, 32, deliver = events::add)
                        pump.pump(32, 32)
                        val index = events.indexOfFirst(predicate)
                        if (index >= 0) return events.removeAt(index)
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0) { "timed out awaiting fleet event" }
                        assertNotNull(notifications.poll(remaining, TimeUnit.NANOSECONDS))
                    }
                }
                fleet.grant(machine, 4, 256)
                val request =
                    (
                        (
                            await {
                                it is ExperimentalFleetEvent.Outcome
                            } as ExperimentalFleetEvent.Outcome
                        ).outcome as VmOutcome.HostRequestBatch
                    ).requests.single()
                val first = await { it is ExperimentalFleetEvent.Quiescent } as ExperimentalFleetEvent.Quiescent
                val firstDelta = credit.finishCumulative(first.retired, 256)
                val remaining = credit.remaining
                // The unresolved host request prevents detachment. The machine must become
                // available for commands again once the rejection reaches the owner.
                assertTrue(pump.outbox.offer(machine, listOf(NativeFleetOutbox.Command.DetachWithTimers(4))))
                pump.pump(0, 1)
                await { it is ExperimentalFleetEvent.DetachRejected }
                assertTrue(
                    pump.outbox.offer(
                        machine,
                        listOf(
                            NativeFleetOutbox.Command.Reply(request.identity, HostResponse.UnitSuccess),
                            NativeFleetOutbox.Command.Grant(4, remaining.toInt(), Long.MAX_VALUE),
                        ),
                    ),
                )
                credit.inFlight = true
                pump.pump(0, 2)
                val halted = await { it is ExperimentalFleetEvent.Outcome } as ExperimentalFleetEvent.Outcome
                assertEquals(VmOutcome.Halted(null), halted.outcome)
                val second = await { it is ExperimentalFleetEvent.Quiescent } as ExperimentalFleetEvent.Quiescent
                val secondDelta = credit.finishCumulative(second.retired, remaining)
                assertEquals(second.retired, firstDelta + secondDelta)
                assertEquals(256 - second.retired, credit.remaining)
                assertTrue(pump.outbox.offer(machine, listOf(NativeFleetOutbox.Command.Stop)))
                pump.pump(0, 1)
                await { it is ExperimentalFleetEvent.Stopped }
            }
            session.close()
        }
    }

    @Test
    fun `dispatcher stops and detaches without delivering another machines queued output`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val owner = Thread.currentThread()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 1, 128, 64, 64, 32)).use { fleet ->
            val stoppedSession = VmSession.open(artifact)
            val detachedSession = VmSession.open(artifact)
            val observer = VmSession.open(artifact)
            val stoppedMachine = stoppedSession.transferToFleet(fleet)
            val detachedMachine = detachedSession.transferToFleet(fleet)
            val observerMachine = observer.transferToFleet(fleet)
            val initialGeneration = fleet.eventGeneration()
            fleet.resourceSnapshot(observerMachine, 42)
            assertTrue(fleet.waitEvents(initialGeneration, 5_000))
            NativeFleetDispatcher(
                fleet,
                8,
                8,
                { check(Thread.currentThread() === owner) },
                {},
                { error("ordinary output must remain queued") },
            ).use { dispatcher ->
                assertTrue(dispatcher.outbox.offer(stoppedMachine, listOf(NativeFleetOutbox.Command.Stop)))
                assertTrue(dispatcher.outbox.offer(detachedMachine, listOf(NativeFleetOutbox.Command.DetachWithTimers(0))))
                assertEquals(0, dispatcher.pump(0, 2))
                val completed = mutableListOf<ExperimentalFleetEvent>()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (completed.size < 2) {
                    check(System.nanoTime() < deadline) { "independent lifecycle stalled behind ordinary output" }
                    dispatcher.pumpLifecycle(2, 2) {
                        check(Thread.currentThread() === owner)
                        completed += it
                    }
                    Thread.yield()
                }
                assertTrue(completed.any { it is ExperimentalFleetEvent.Stopped && it.machine == stoppedMachine })
                assertTrue(completed.any { it is ExperimentalFleetEvent.Detached && it.machine == detachedMachine })
                VmSession.restoreFromFleet(fleet, detachedMachine).close()
                // Nothing from the unrelated VM was discarded or delivered as part of close.
                val pending = fleet.poll() as ExperimentalFleetEvent.Resources
                assertEquals(observerMachine, pending.machine)
                assertEquals(42L, pending.request)
                assertEquals(null, fleet.poll())
                assertTrue(dispatcher.outbox.offer(observerMachine, listOf(NativeFleetOutbox.Command.Stop)))
                dispatcher.pump(0, 1)
                var observerStopped = false
                while (!observerStopped) {
                    check(System.nanoTime() < deadline)
                    dispatcher.pumpLifecycle(1, 1) {
                        assertTrue(it is ExperimentalFleetEvent.Stopped && it.machine == observerMachine)
                        observerStopped = true
                    }
                    Thread.yield()
                }
            }
            stoppedSession.close()
            detachedSession.close()
            observer.close()
        }
    }

    @Test
    fun `native actor service runs timer turns through the carrier contract and stops its host`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val host = ProgramRuntimeHost(NativeProgramVmSessionFactory())
        assertEquals(ProgramStartResult.Started, host.start(artifact))
        val owner = Thread.currentThread()
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val machine = host.transferToFleet(fleet, 0)
            val endpoint = VmActorEndpoint(ComputerId.fromLongs(31, 32), 1)
            NativeFleetActorRuntimeService(
                fleet,
                4,
                16,
                16,
                { check(Thread.currentThread() === owner) },
                {},
                { _, _ -> },
                onActorClosing = {},
            ).use { service ->
                assertTrue(service.register(endpoint, machine, host))
                val runtime: ActorRuntimeService = service

                fun <T> await(future: CompletableFuture<T>): T {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!future.isDone) {
                        check(System.nanoTime() < deadline) { "native actor turn stalled" }
                        service.pump(32, 32)
                        Thread.yield()
                    }
                    return future.join()
                }
                val replies =
                    (1L..3L).map { tick ->
                        assertTrue(service.grantFrame(endpoint, tick, 512))
                        val result = CompletableFuture<ProgramRuntimeActorReply>()
                        assertEquals(
                            VmActorSubmission.ACCEPTED,
                            runtime.submitTurn(endpoint, tick, emptyList()) { reply, failure ->
                                check(Thread.currentThread() === owner)
                                if (failure == null) result.complete(checkNotNull(reply)) else result.completeExceptionally(failure)
                            },
                        )
                        await(result).also { assertTrue(!service.grantFrame(endpoint, tick, 512)) }
                    }
                assertEquals(ProgramRuntimeState.Running, replies[0].state)
                assertEquals(0L, replies[1].retiredInstructions)
                assertEquals(ProgramRuntimeState.Halted(null), replies[2].state)
                // The same artifact and tick sequence produce the same retirement count on
                // the existing host path, although only Rust workers execute in this service.
                ProgramRuntimeHost(NativeProgramVmSessionFactory()).use { baseline ->
                    assertEquals(ProgramStartResult.Started, baseline.start(artifact))
                    var retired = 0L
                    for (tick in 1L..3L) {
                        baseline.serverTick(tick, 512)
                        retired += baseline.retiredInstructionsLastTick
                    }
                    assertEquals(replies.sumOf { it.retiredInstructions }, retired)
                    assertEquals(baseline.state, host.state)
                }
                val generation = await(runtime.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration))
                val stopped = await(checkNotNull(service.stop(endpoint)))
                assertEquals(generation.fileSystemGeneration, stopped)
                assertEquals(stopped, host.lastClosedFileSystemGeneration)
                assertEquals(ProgramRuntimeState.Closed, host.state)
            }
        }
    }

    @Test
    fun `native actor streams addon request and continues within the original frame`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.host.fixture"))).readBytes()
        val schema =
            HostCapabilitySchema(
                CapabilityIdentity("app", "entry", 1, 2),
                listOf(HostOperationSchema(emptyList(), HostValueType.UNIT, true)),
            )
        val host =
            ProgramRuntimeHost(NativeProgramVmSessionFactory(capabilitySchemas = listOf(schema)), addonCapabilitySchemas = listOf(schema))
        assertEquals(ProgramStartResult.Started, host.start(artifact))
        VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
            val machine = host.transferToFleet(fleet, 0)
            val endpoint = VmActorEndpoint(ComputerId.fromLongs(33, 34), 1)
            var request: VmHostRequestIdentity? = null
            NativeFleetActorRuntimeService(fleet, 4, 16, 16, {}, {}, { address, event ->
                assertEquals(endpoint, address)
                if (event is ExperimentalFleetEvent.Outcome) {
                    val outcome = event.outcome
                    check(outcome !is VmOutcome.HostRequestBatch) { "managed addon request delivered twice" }
                }
            }, onActorClosing = {}).use { service ->
                assertTrue(service.register(endpoint, machine, host))

                fun <T> await(future: CompletableFuture<T>): T {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!future.isDone) {
                        check(System.nanoTime() < deadline)
                        service.pump(32, 32)
                        Thread.yield()
                    }
                    return future.join()
                }
                assertTrue(service.grantFrame(endpoint, 4, 256))
                val first = CompletableFuture<ProgramRuntimeActorReply>()
                assertEquals(
                    VmActorSubmission.ACCEPTED,
                    service.submitTurn(
                        endpoint,
                        4,
                        emptyList(),
                        object : ProgramRuntimeTurnObserver {
                            override fun addonAction(action: ProgramAddonAction) {
                                val addon = assertIs<ProgramAddonAction.Request>(action).request
                                assertEquals(0L, addon.programId)
                                assertTrue(!first.isDone)
                                check(request == null)
                                request = addon.identity
                            }

                            override fun complete(
                                reply: ProgramRuntimeActorReply?,
                                failure: Throwable?,
                            ) {
                                if (failure == null) first.complete(checkNotNull(reply)) else first.completeExceptionally(failure)
                            }
                        },
                    ),
                )
                val before = await(first)
                val runtime: ActorRuntimeService = service
                // Streaming does not settle credit; only the final report permits this continuation.
                val completion =
                    ProgramRuntimeActorEffect.CompleteAddons(
                        listOf(
                            ProgramAddonCompletion(checkNotNull(request), HostResponse.UnitSuccess),
                        ),
                    )
                val second = CompletableFuture<ProgramRuntimeActorReply>()
                assertTrue(
                    runtime.continueTurn(endpoint, 4, listOf(completion, completion)) { reply, failure ->
                        if (failure == null) second.complete(checkNotNull(reply)) else second.completeExceptionally(failure)
                    },
                )
                val after = await(second)
                assertEquals(ProgramRuntimeState.Halted(null), after.state)
                assertTrue(before.retiredInstructions > 0 && after.retiredInstructions > 0)
                assertTrue(before.retiredInstructions + after.retiredInstructions <= 256)
                await(checkNotNull(service.stop(endpoint)))
            }
        }
    }

    @Test
    fun `native actor terminal requests report actual acceptance before the next grant`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.terminal.fixture"))).readBytes()
        for (canonical in listOf(false, true)) {
            val host = ProgramRuntimeHost(NativeProgramVmSessionFactory())
            assertEquals(ProgramStartResult.Started, host.start(artifact))
            VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 1024, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                val machine = host.transferToFleet(fleet, 0)
                val endpoint = VmActorEndpoint(ComputerId.fromLongs(35, 36), 1)
                val owner = Thread.currentThread()
                val checkOwner = { check(Thread.currentThread() === owner) }
                NativeFleetActorRuntimeService(fleet, 4, 16, 16, checkOwner, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                    assertTrue(service.register(endpoint, machine, host))
                    val runtime: ActorRuntimeService = service

                    fun <T> await(future: CompletableFuture<T>): T {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!future.isDone) {
                            check(System.nanoTime() < deadline) { "terminal request stalled" }
                            service.pump(32, 32)
                            Thread.yield()
                        }
                        return future.join()
                    }

                    fun turn(tick: Long): ProgramRuntimeActorReply {
                        assertTrue(service.grantFrame(endpoint, tick, 512))
                        val result = CompletableFuture<ProgramRuntimeActorReply>()
                        assertEquals(
                            VmActorSubmission.ACCEPTED,
                            runtime.submitTurn(endpoint, tick, emptyList()) { reply, failure ->
                                if (failure == null) result.complete(checkNotNull(reply)) else result.completeExceptionally(failure)
                            },
                        )
                        return await(result)
                    }
                    val premature =
                        await(runtime.request(endpoint) { ProgramRuntimeActorCommand.SubmitCanonicalLine(it, "hello".toCharArray()) })
                    assertEquals(
                        ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.CanonicalLine(VmCanonicalLineFailure.NO_PENDING_READ)),
                        premature.value,
                    )
                    assertEquals(ProgramRuntimeState.WaitingForInput, turn(1).state)
                    val full =
                        assertIs<ProgramRuntimeActorValue.TerminalStateValue>(
                            await(runtime.request(endpoint, ProgramRuntimeActorCommand::TerminalFullState)).value,
                        ).state!!
                    assertEquals(
                        TerminalUpdate.Unchanged(full.revision),
                        assertIs<ProgramRuntimeActorValue.TerminalUpdateValue>(
                            await(runtime.request(endpoint) { ProgramRuntimeActorCommand.TerminalChangesSince(it, full.revision) }).value,
                        ).update,
                    )
                    if (canonical) {
                        val accepted =
                            await(runtime.request(endpoint) { ProgramRuntimeActorCommand.SubmitCanonicalLine(it, "hello".toCharArray()) })
                        assertEquals(ProgramRuntimeActorValue.Accepted(true), accepted.value)
                        assertEquals(ProgramRuntimeState.Running, accepted.state)
                    } else {
                        assertEquals(
                            ProgramRuntimeActorValue.Accepted(true),
                            await(
                                runtime.request(endpoint) {
                                    ProgramRuntimeActorCommand.SendTerminalText(it, "hello")
                                },
                            ).value,
                        )
                        assertEquals(
                            ProgramRuntimeActorValue.Accepted(true),
                            await(
                                runtime.request(endpoint) {
                                    ProgramRuntimeActorCommand.SendTerminalKey(it, TerminalKey.ENTER, TerminalKeyAction.PRESS, emptySet())
                                },
                            ).value,
                        )
                    }
                    // Input acknowledgements update observations but coordinated mode does not
                    // execute the resumed Guest task until another capacity-owner grant arrives.
                    assertEquals(
                        ProgramRuntimeState.Running,
                        await(runtime.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)).state,
                    )
                    var state: ProgramRuntimeState = host.state
                    for (tick in 2L..128L) {
                        state = turn(tick).state
                        if (state is ProgramRuntimeState.Halted) break
                    }
                    assertEquals(ProgramRuntimeState.Halted(VmValue.I32("hello".hashCode())), state)
                    assertEquals(
                        ProgramRuntimeActorValue.Accepted(false),
                        await(
                            runtime.request(endpoint) {
                                ProgramRuntimeActorCommand.SendTerminalText(it, "after halt")
                            },
                        ).value,
                    )
                    await(checkNotNull(service.stop(endpoint)))
                }
            }
        }
    }

    @Test
    fun `native actor filesystem queries match the original session and preserve typed failures`() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
        val artifact = Path.of(requireNotNull(System.getProperty("compukters.fleet.timer.fixture"))).readBytes()
        val path = VmVirtualPath.of("/bin/sample")
        val encodedPath = path.value.encodeToByteArray()
        val payload =
            ByteBuffer
                .allocate(32 + encodedPath.size + artifact.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("CPKTSYS\u0000".encodeToByteArray())
                .putShort(1)
                .putShort(0)
                .putInt(1)
                .putInt(
                    encodedPath.size,
                ).put(encodedPath)
                .put(2.toByte())
                .put(1.toByte())
                .putShort(0)
                .putLong(artifact.size.toLong())
                .put(artifact)
                .array()
        val image = VmSystemImage.of(payload + MessageDigest.getInstance("SHA-256").digest(payload))
        val root = Files.createTempDirectory("fleet-carrier-files-").toRealPath()
        try {
            WorldFileSystemStore.open(root).use { store ->
                val id = ComputerId.fromLongs(37, 38)
                val host = ProgramRuntimeHost(NativeProgramVmSessionFactory(ProgramFileSystemLaunchContext(store, id, image)))
                assertEquals(ProgramStartResult.Started, host.start(artifact))
                val stat = host.fileStat(path)!!
                val listing = host.fileList(VmVirtualPath.of("/bin"), null, 256)!!
                val chunk = host.fileRead(path, 0, 32, stat.metadata.generation)!!
                VmRuntime.openExperimentalFleet(ExperimentalFleetConfig(1, 32, 32, 128, 64, 64, 32, coordinatedTurns = true)).use { fleet ->
                    val machine = host.transferToFleet(fleet, 0)
                    val endpoint = VmActorEndpoint(id, 1)
                    NativeFleetActorRuntimeService(fleet, 4, 16, 16, {}, {}, { _, _ -> }, onActorClosing = {}).use { service ->
                        assertTrue(service.register(endpoint, machine, host))

                        fun <T> await(future: CompletableFuture<T>): T {
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (!future.isDone) {
                                check(System.nanoTime() < deadline) { "filesystem request stalled" }
                                service.pump(32, 32)
                                Thread.yield()
                            }
                            return future.join()
                        }
                        val runtime: ActorRuntimeService = service
                        val statReply = runtime.request(endpoint) { ProgramRuntimeActorCommand.FileStat(it, path) }
                        val listReply =
                            runtime.request(
                                endpoint,
                            ) { ProgramRuntimeActorCommand.FileList(it, VmVirtualPath.of("/bin"), null, 256) }
                        val readReply =
                            runtime.request(
                                endpoint,
                            ) { ProgramRuntimeActorCommand.FileRead(it, path, 0, 32, stat.metadata.generation) }
                        val stale =
                            runtime.request(endpoint) {
                                ProgramRuntimeActorCommand.FileRead(
                                    it,
                                    path,
                                    0,
                                    32,
                                    stat.metadata.generation + 1,
                                )
                            }
                        val missing =
                            runtime.request(
                                endpoint,
                            ) { ProgramRuntimeActorCommand.FileStat(it, VmVirtualPath.of("/home/missing")) }
                        assertEquals(stat, assertIs<ProgramRuntimeActorValue.FileStatValue>(await(statReply).value).stat)
                        assertEquals(listing, assertIs<ProgramRuntimeActorValue.FileListValue>(await(listReply).value).listing)
                        assertEquals(chunk, assertIs<ProgramRuntimeActorValue.FileChunkValue>(await(readReply).value).chunk)
                        assertEquals(
                            ProgramRuntimeActorValue.Rejected(
                                ProgramRuntimeActorFailure.FileSystem(VmFileSystemReadFailure.STALE_GENERATION),
                            ),
                            await(stale).value,
                        )
                        assertEquals(
                            ProgramRuntimeActorValue.Rejected(ProgramRuntimeActorFailure.FileSystem(VmFileSystemReadFailure.NOT_FOUND)),
                            await(missing).value,
                        )
                        assertEquals(ProgramRuntimeState.Running, host.state)
                        await(checkNotNull(service.stop(endpoint)))
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
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
            NativeFleetEventPump(
                fleet,
                { check(Thread.currentThread() === owner) },
                { notifications.offer(Unit) },
                events::add,
            ).use { pump ->
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
