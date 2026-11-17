/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor.integration

import ru.lazyhat.compukters.core.device.computer.ActorProgramComputer
import ru.lazyhat.compukters.core.device.runtime.actor.NativeProgramRuntimeService
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorLease
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorReply
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorValue
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.actor.VmExecutionBackend
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.RedstoneCommitResult
import ru.lazyhat.compukters.core.device.runtime.program.integration.installFfmRuntime
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeProgramRuntimeServiceIntegrationTest {
    @Test
    fun `command only pumps deliver credit before expiry without completing owner observations`() {
        install()
        NativeProgramRuntimeService(config()).use { service ->
            assertTrue(service.supportsCommandOnlyPump)
            val endpoint = endpoint(13)
            val lease = start(service, endpoint, "timer")
            var retired = 0L
            for (tick in 1L..20L) {
                service.beginCapacityFrame(tick)
                val pending = service.turn(endpoint, tick)
                service.flushCapacityFrame()
                assertEquals(0, service.pump(0, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2)))
                assertFalse(pending.isDone, "sending credit must not complete an owner observation")
                // A grant first sent by the later result pump would already have expired.
                Thread.sleep(10)
                assertFalse(pending.isDone)
                retired += await(service, pending).retiredInstructions
                if (retired > 0) break
            }
            assertTrue(retired > 0, "zero-event pumping must send credit before its 2.5 ms deadline")
            lease.closeAsync().get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `owner notifications dispatch ordinary grants within the server deadline without another tick`() {
        install()
        val notifications = LinkedBlockingQueue<Unit>()
        NativeProgramRuntimeService(config(), onResultsReady = { notifications.offer(Unit) }).use { service ->
            val endpoint = endpoint(12)
            val lease = start(service, endpoint, "timer")
            var pending: CompletableFuture<ProgramRuntimeActorReply>? = null
            var tick = 0L
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (service.runtimeMetrics().retiredInstructionsTotal == 0L && System.nanoTime() < deadline) {
                val nextTick = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)
                service.pump(32)
                service.beginCapacityFrame(++tick)
                if (pending == null || pending.isDone) pending = service.turn(endpoint, tick)
                service.flushCapacityFrame()
                var pumpNanos = 0L
                while (System.nanoTime() < nextTick) {
                    val wait = nextTick - System.nanoTime()
                    if (wait <= 0) break
                    if (pumpNanos < TimeUnit.MILLISECONDS.toNanos(2) && notifications.poll(wait, TimeUnit.NANOSECONDS) != null) {
                        val started = System.nanoTime()
                        service.pump(32, started + TimeUnit.MILLISECONDS.toNanos(2) - pumpNanos)
                        pumpNanos += System.nanoTime() - started
                    } else {
                        LockSupport.parkNanos((nextTick - System.nanoTime()).coerceAtLeast(0))
                    }
                }
            }
            assertTrue(
                service.runtimeMetrics().retiredInstructionsTotal > 0,
                "notification must dispatch credit before its 2.5 ms deadline",
            )
            lease.closeAsync().get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `server service bootstraps an artifact and retires only after a capacity frame`() {
        install()
        NativeProgramRuntimeService(config(), safeInstructionCapacity = 512).use { service ->
            val endpoint = endpoint(1)
            val lease = start(service, endpoint, "timer")
            val before = await(service, service.request(endpoint, ProgramRuntimeActorCommand::ResourceSnapshot))
            service.beginCapacityFrame(1)
            val turn = service.turn(endpoint, 1)
            repeat(10) { service.pump(32) }
            assertFalse(turn.isDone)
            assertEquals(0L, service.runtimeMetrics().retiredInstructionsTotal)
            service.flushCapacityFrame()
            val first = await(service, turn)
            assertEquals(ProgramRuntimeState.Running, first.state)
            assertTrue(first.retiredInstructions in 1..512)
            assertTrue(service.turn(endpoint, 1).isCompletedExceptionally, "duplicate ordinary turn must reject instead of waiting forever")
            val second = frame(service, endpoint, 2)
            assertEquals(0L, second.retiredInstructions)
            val third = frame(service, endpoint, 3)
            assertEquals(ProgramRuntimeState.Halted(null), third.state)
            assertTrue(before.retiredInstructions == 0L)
            assertEquals(first.retiredInstructions + third.retiredInstructions, service.runtimeMetrics().retiredInstructionsTotal)
            assertEquals(VmExecutionBackend.NATIVE, service.runtimeMetrics().backend)
            assertNull(service.runtimeMetrics().scheduler)
            lease.closeAsync().get(5, TimeUnit.SECONDS)
            assertEquals(0, service.runtimeMetrics().nativeActors)
        }
    }

    @Test
    fun `native capacity is shared with rotating single instruction grants`() {
        install()
        NativeProgramRuntimeService(config(), perComputerEntitlement = 10, safeInstructionCapacity = 2).use { service ->
            val endpoints = (1L..3L).map(::endpoint)
            endpoints.forEach { start(service, it, "timer") }
            val totals = endpoints.associateWith { 0L }.toMutableMap()
            for (tick in 1L..3L) {
                service.beginCapacityFrame(tick)
                val pending = endpoints.map { it to service.turn(it, tick) }
                service.flushCapacityFrame()
                val replies = pending.map { (endpoint, future) -> endpoint to await(service, future) }
                assertEquals(2L, replies.sumOf { it.second.retiredInstructions })
                assertEquals(2L, service.runtimeMetrics().reservedInstructionsLastFrame)
                replies.forEach { (endpoint, reply) -> totals[endpoint] = totals.getValue(endpoint) + reply.retiredInstructions }
            }
            assertEquals(setOf(2L), totals.values.toSet())
        }
    }

    @Test
    fun `terminal input wakes a waiting native computer without a second authority for credit`() {
        install()
        NativeProgramRuntimeService(config(), safeInstructionCapacity = 512).use { service ->
            val endpoint = endpoint(5)
            start(service, endpoint, "terminal")
            assertEquals(ProgramRuntimeState.WaitingForInput, frame(service, endpoint, 1).state)
            val input =
                await(service, service.request(endpoint) { ProgramRuntimeActorCommand.SubmitCanonicalLine(it, "hello".toCharArray()) })
            assertEquals(ProgramRuntimeActorValue.Accepted(true), input.value)
            assertEquals(ProgramRuntimeState.Running, input.state)
            var state: ProgramRuntimeState = input.state
            for (tick in 2L..128L) {
                val reply = frame(service, endpoint, tick)
                assertTrue(reply.retiredInstructions <= 512)
                state = reply.state
                if (state is ProgramRuntimeState.Halted) break
            }
            assertEquals(
                ProgramRuntimeState.Halted(
                    ru.lazyhat.compukters.lang.runtime.vm.VmValue
                        .I32("hello".hashCode()),
                ),
                state,
            )
        }
    }

    @Test
    fun `closing a collected native turn cancels it without owner pumping`() {
        install()
        NativeProgramRuntimeService(config(), safeInstructionCapacity = 512).use { service ->
            val endpoint = endpoint(8)
            val lease = start(service, endpoint, "timer")
            service.beginCapacityFrame(1)
            val pending = service.turn(endpoint, 1)
            lease.closeAsync().get(5, TimeUnit.SECONDS)
            assertTrue(pending.isCompletedExceptionally)
            service.flushCapacityFrame()
            assertEquals(0L, service.runtimeMetrics().reservedInstructionsLastFrame)
            assertEquals(0, service.runtimeMetrics().nativeActors)
        }
    }

    @Test
    fun `native close after shutdown fences the reused bootstrap lease`() {
        install()
        NativeProgramRuntimeService(config(), safeInstructionCapacity = 512).use { service ->
            val endpoint = endpoint(9)
            val lease = start(service, endpoint, "timer")
            frame(service, endpoint, 1)
            val shutdown = await(service, service.request(endpoint, ProgramRuntimeActorCommand::Shutdown))
            assertEquals(ProgramRuntimeState.Idle, shutdown.state)
            assertEquals(1, service.runtimeMetrics().nativeActors)
            lease.closeAsync().get(5, TimeUnit.SECONDS)
            assertEquals(0, service.runtimeMetrics().nativeActors)
        }
    }

    @Test
    fun `expired server frames finish without retiring Guest instructions`() {
        install()
        // The transport translates this past JVM deadline to zero native time allowance.
        val past = System.nanoTime() - TimeUnit.SECONDS.toNanos(10)
        NativeProgramRuntimeService(config(), nanoTime = { past }).use { service ->
            val endpoint = endpoint(10)
            start(service, endpoint, "timer")
            val first = frame(service, endpoint, 1)
            assertEquals(0L, first.retiredInstructions)
            assertEquals(ProgramRuntimeState.Running, first.state)
            assertEquals(0L, frame(service, endpoint, 2).retiredInstructions)
        }
    }

    @Test
    fun `native carrier delivers redstone with one mailbox slot`() {
        install()
        val config = VmActorSchedulerConfig(workerCount = 1, maximumActors = 1, mailboxCapacity = 1, resultCapacityPerWorker = 1)
        NativeProgramRuntimeService(config, safeInstructionCapacity = 512).use { service ->
            val endpoint = endpoint(11)
            val lease = start(service, endpoint, "timer")
            val carrier = ActorProgramComputer(service, lease, { RedstoneCommitResult.Committed })
            await(service, carrier.resourceSnapshot())
            service.beginCapacityFrame(1)
            carrier.serverTick(1, 1)
            service.flushCapacityFrame()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (service.runtimeMetrics().retiredInstructionsTotal == 0L && System.nanoTime() < deadline) {
                service.pump(32)
                Thread.yield()
            }
            assertTrue(service.runtimeMetrics().retiredInstructionsTotal > 0, "redstone and Grant cannot deadlock a one-slot mailbox")
            carrier.closeAsync().get(5, TimeUnit.SECONDS)
        }
    }

    private fun install() {
        installFfmRuntime()
        VmRuntime.loadNativeLibrary(Path.of(requireNotNull(System.getProperty("compukters.ffi.library"))))
    }

    private fun config() = VmActorSchedulerConfig(workerCount = 2, maximumActors = 8, mailboxCapacity = 4, resultCapacityPerWorker = 8)

    private fun endpoint(id: Long) = VmActorEndpoint(ComputerId.fromLongs(716, id), 1)

    private fun start(
        service: NativeProgramRuntimeService,
        endpoint: VmActorEndpoint,
        fixture: String,
    ): ProgramRuntimeActorLease {
        val lease = assertNotNull(service.attachStandalone(endpoint))
        val bytes = Path.of(requireNotNull(System.getProperty("compukters.fleet.$fixture.fixture"))).readBytes()
        val result = await(service, service.request(endpoint) { ProgramRuntimeActorCommand.Start(it, bytes) })
        assertEquals(ProgramStartResult.Started, assertIs<ProgramRuntimeActorValue.Start>(result.value).result)
        return lease
    }

    private fun frame(
        service: NativeProgramRuntimeService,
        endpoint: VmActorEndpoint,
        tick: Long,
    ): ProgramRuntimeActorReply {
        service.beginCapacityFrame(tick)
        val pending = service.turn(endpoint, tick)
        service.flushCapacityFrame()
        return await(service, pending)
    }

    private fun <T> await(
        service: NativeProgramRuntimeService,
        future: CompletableFuture<T>,
    ): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!future.isDone) {
            check(System.nanoTime() - deadline < 0) { "native server service stalled" }
            service.pump(32)
            Thread.yield()
        }
        return future.join()
    }
}
