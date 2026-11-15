/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSession
import ru.lazyhat.compukters.core.device.runtime.program.ProgramVmSessionFactory
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFleetActorRuntimeServiceTest {
    @Test
    fun `frame is not refilled and continuation reports only newly retired instructions`() =
        Harness().use { h ->
            val endpoint = h.register(1)
            val replies = mutableListOf<ProgramRuntimeActorReply>()
            val completion =
                ProgramRuntimeTurnCompletion { reply, error ->
                    check(error == null)
                    replies += checkNotNull(reply)
                }
            assertTrue(h.service.grantFrame(endpoint, 4, 20))
            val runtime: ActorRuntimeService = h.service
            assertEquals(VmActorSubmission.ACCEPTED, runtime.submitTurn(endpoint, 4, emptyList(), completion))
            assertFalse(h.service.grantFrame(endpoint, 5, 100))
            assertEquals(VmActorSubmission.PERMIT_PENDING, runtime.submitTurn(endpoint, 4, emptyList(), completion))
            h.service.pump(0, 10)
            h.events += ExperimentalFleetEvent.Quiescent(1, 4, 6, false)
            h.service.pump(10, 10)
            assertFalse(h.service.grantFrame(endpoint, 4, 100))
            assertTrue(runtime.continueTurn(endpoint, 4, emptyList(), completion))
            h.service.pump(0, 10)
            h.events += ExperimentalFleetEvent.Quiescent(1, 4, 10, false)
            h.service.pump(10, 10)
            assertEquals(listOf(20, 14), h.grants.map { it.second })
            assertEquals(listOf(6L, 4L), replies.map { it.retiredInstructions })
            // A duplicate report after settlement completes nothing and creates no new grant.
            h.events += ExperimentalFleetEvent.Quiescent(1, 4, 10, false)
            h.service.pump(10, 10)
            assertEquals(2, replies.size)
            assertTrue(h.service.grantFrame(endpoint, 5, 3))
            assertEquals(VmActorSubmission.ACCEPTED, runtime.submitTurn(endpoint, 5, emptyList(), completion))
        }

    @Test
    fun `cancellation retains query admission until its native response arrives`() =
        Harness(maximumRequests = 2).use { h ->
            val endpoint = h.register(1)

            fun query() = h.service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
            val first = query()
            val second = query()
            assertTrue(first.cancel(false))
            assertTrue(second.cancel(false))
            assertTrue(query().isCompletedExceptionally)
            h.service.pump(0, 10)
            val sent = h.queries.toList()
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, sent[0], 7)
            h.service.pump(1, 0)
            assertFalse(query().isCompletedExceptionally)
            // Removing another response frees its slot too; canceled futures stay canceled.
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, sent[1], 8)
            h.service.pump(1, 0)
            assertTrue(first.isCancelled && second.isCancelled)
        }

    @Test
    fun `stop cancels pending work and stale events cannot reach a replacement endpoint`() =
        Harness().use { h ->
            val original = h.register(1)
            val query = h.service.request(original, ProgramRuntimeActorCommand::FileSystemGeneration)
            val reply = CompletableFuture<ProgramRuntimeActorReply>()
            assertTrue(h.service.grantFrame(original, 1, 20))
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(original, 1, emptyList()) { value, failure ->
                    if (failure == null) reply.complete(value) else reply.completeExceptionally(failure)
                },
            )
            val stopped = checkNotNull(h.service.stop(original))
            assertEquals(VmActorSubmission.CLOSED, h.service.submitTurn(original, 1, emptyList()) { _, _ -> error("not accepted") })
            h.service.pump(0, 10)
            h.service.pump(10, 10)
            assertEquals(7L, stopped.join())
            assertTrue(query.isCompletedExceptionally && reply.isCompletedExceptionally)
            h.observed.clear()
            val replacement = h.register(2, original.computerId, 2)
            h.events += ExperimentalFleetEvent.Outcome(1, 1, VmOutcome.Halted(null))
            h.events += ExperimentalFleetEvent.Quiescent(1, 1, 20, false)
            h.events += ExperimentalFleetEvent.Error(1, "late failure from a stopped machine")
            h.events += ExperimentalFleetEvent.FilesystemGeneration(1, h.queries.first(), 999)
            h.service.pump(10, 10)
            assertTrue(h.observed.isEmpty())
            assertTrue(h.service.grantFrame(replacement, 2, 10))
            assertFalse(h.service.grantFrame(original, 2, 10))
        }

    @Test
    fun `expired grants reject without callbacks and a callback failure does not block another actor`() =
        Harness().use { h ->
            val a = h.register(1)
            val b = h.register(2)
            assertTrue(h.service.grantFrame(a, 1, 10, 100))
            assertEquals(VmActorSubmission.MAILBOX_FULL, h.service.submitTurn(a, 1, emptyList()) { _, _ -> error("expired") })
            assertTrue(h.service.grantFrame(a, 2, 10))
            assertTrue(h.service.grantFrame(b, 2, 10))
            assertEquals(VmActorSubmission.ACCEPTED, h.service.submitTurn(a, 2, emptyList()) { _, _ -> error("observer failed") })
            var completed = false
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(b, 2, emptyList()) { _, error ->
                    check(error == null)
                    completed =
                        true
                },
            )
            h.service.pump(0, 10)
            h.events += ExperimentalFleetEvent.Quiescent(1, 2, 3, false)
            h.events += ExperimentalFleetEvent.Quiescent(2, 2, 4, false)
            assertEquals(2, h.service.pump(10, 10))
            assertTrue(completed)
            assertFailsWith<IllegalStateException> { h.service.closeAsync() }
            Unit
        }

    @Test
    fun `refused turn leaves its frame available and stop retries without losing accepted queries`() =
        Harness(maximumRequests = 1).use { h ->
            val endpoint = h.register(1)
            assertTrue(h.service.grantFrame(endpoint, 1, 10))
            val query = h.service.request(endpoint, ProgramRuntimeActorCommand::FileSystemGeneration)
            assertEquals(VmActorSubmission.MAILBOX_FULL, h.service.submitTurn(endpoint, 1, emptyList()) { _, _ -> error("rejected") })
            assertEquals(null, h.service.stop(endpoint))
            h.service.pump(0, 1)
            var canceled = false
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(endpoint, 1, emptyList()) { _, failure -> canceled = failure != null },
            )
            h.service.pump(0, 1)
            val first = checkNotNull(h.service.stop(endpoint))
            first.cancel(false)
            val second = checkNotNull(h.service.stop(endpoint))
            h.service.pump(0, 1)
            h.service.pump(1, 1)
            assertEquals(7L, second.join())
            assertTrue(first.isCancelled && query.isCompletedExceptionally && canceled)
        }

    @Test
    fun `failed host cleanup fails its barrier and releases pending requests without blocking another stop`() =
        Harness(closeFailureFor = 1).use { h ->
            val a = h.register(1)
            val b = h.register(2)
            val query = h.service.request(a, ProgramRuntimeActorCommand::FileSystemGeneration)
            val failed = checkNotNull(h.service.stop(a))
            val succeeded = checkNotNull(h.service.stop(b))
            h.service.pump(0, 16)
            h.service.pump(16, 16)
            assertTrue(failed.isCompletedExceptionally && query.isCompletedExceptionally)
            assertEquals(7L, succeeded.join())
        }

    @Test
    fun `ordered input wakes host observations without calling the transferred session`() =
        Harness().use { h ->
            val endpoint = h.register(1)
            val states = mutableListOf<ProgramRuntimeState>()
            val completion = ProgramRuntimeTurnCompletion { reply, failure -> if (failure == null) states += checkNotNull(reply).state }
            assertTrue(h.service.grantFrame(endpoint, 1, 10))
            assertEquals(VmActorSubmission.ACCEPTED, h.service.submitTurn(endpoint, 1, emptyList(), completion))
            h.service.pump(0, 8)
            h.events += ExperimentalFleetEvent.Outcome(1, 1, VmOutcome.WaitingForTerminalEvent)
            h.events += ExperimentalFleetEvent.Quiescent(1, 1, 0, false)
            h.service.pump(8, 8)
            assertTrue(h.service.grantFrame(endpoint, 2, 10))
            assertEquals(
                VmActorSubmission.ACCEPTED,
                h.service.submitTurn(endpoint, 2, listOf(ProgramRuntimeActorEffect.RedstoneInput(0)), completion),
            )
            h.service.pump(0, 8)
            h.events += ExperimentalFleetEvent.Quiescent(1, 2, 0, false)
            h.service.pump(8, 8)
            assertEquals(listOf(ProgramRuntimeState.WaitingForInput, ProgramRuntimeState.Running), states)
        }

    @Test
    fun `cancellation callback failure still closes host and fails its barrier`() =
        Harness(cancellationFailureFor = 1).use { h ->
            val endpoint = h.register(1)
            val stopped = checkNotNull(h.service.stop(endpoint))
            h.service.pump(0, 8)
            h.service.pump(8, 8)
            assertTrue(stopped.isCompletedExceptionally)
            assertEquals(listOf(1L), h.closedMachines)
        }

    @Test
    fun `automatic native execution is rejected before allocating a dispatcher`() {
        val uncoordinated =
            Proxy.newProxyInstance(ExperimentalFleet::class.java.classLoader, arrayOf(ExperimentalFleet::class.java)) { _, method, _ ->
                check(method.name == "getCoordinatedTurns") { "uncoordinated fleet must not start a dispatcher" }
                false
            } as ExperimentalFleet
        assertFailsWith<IllegalArgumentException> {
            NativeFleetActorRuntimeService(uncoordinated, 4, 16, 16, {}, {}, { _, _ -> })
        }
    }

    private class Harness(
        maximumRequests: Int = 16,
        private val closeFailureFor: Long? = null,
        private val cancellationFailureFor: Long? = null,
    ) : AutoCloseable {
        val events = ArrayDeque<ExperimentalFleetEvent>()
        val grants = mutableListOf<Pair<Long, Int>>()
        val queries = mutableListOf<Long>()
        val observed = mutableListOf<VmActorEndpoint>()
        val closedMachines = mutableListOf<Long>()
        private val endpoints = mutableListOf<VmActorEndpoint>()
        private val stops = mutableMapOf<Long, ExperimentalFleetEvent>()
        private val owner = Thread.currentThread()
        private val fleet =
            Proxy.newProxyInstance(ExperimentalFleet::class.java.classLoader, arrayOf(ExperimentalFleet::class.java)) { _, method, args ->
                when (method.name) {
                    "getCoordinatedTurns" -> {
                        true
                    }

                    "redstoneInput" -> {
                        null
                    }

                    "eventGeneration" -> {
                        0L
                    }

                    "waitEvents" -> {
                        TimeUnit.MILLISECONDS.sleep(10)
                        false
                    }

                    "poll" -> {
                        events.pollFirst()
                    }

                    "grant" -> {
                        grants += (args!![0] as Long) to (args[2] as Int)
                        null
                    }

                    "filesystemGeneration" -> {
                        queries += args!![1] as Long
                        null
                    }

                    "stop" -> {
                        stops[args!![0] as Long] = ExperimentalFleetEvent.Stopped(args[0] as Long, emptyList(), 7)
                        null
                    }

                    "pollLifecycle" -> {
                        stops.remove(args!![0] as Long)
                    }

                    else -> {
                        error("unexpected fleet call ${method.name}")
                    }
                }
            } as ExperimentalFleet
        val service =
            NativeFleetActorRuntimeService(fleet, 4, maximumRequests, maximumRequests, {
                check(
                    Thread.currentThread() === owner,
                )
            }, {}, { endpoint, event ->
                observed += endpoint
                check(event !is ExperimentalFleetEvent.Stopped || event.machine != cancellationFailureFor) { "cancellation failed" }
            }, { 100 })

        fun register(
            machine: Long,
            computerId: ComputerId = ComputerId.fromLongs(0, machine),
            epoch: Long = 1,
        ): VmActorEndpoint {
            val session =
                Proxy.newProxyInstance(ProgramVmSession::class.java.classLoader, arrayOf(ProgramVmSession::class.java)) { _, method, _ ->
                    when (method.name) {
                        "transferToFleet" -> {
                            machine
                        }

                        "filesystemGeneration" -> {
                            7L
                        }

                        "confirmRedstoneOutput", "submitRedstoneInput", "acknowledgeFleetStop" -> {
                            null
                        }

                        "close" -> {
                            closedMachines += machine
                            check(machine != closeFailureFor) { "host close failed" }
                            null
                        }

                        else -> {
                            error("unexpected session call ${method.name}")
                        }
                    }
                } as ProgramVmSession
            val factory =
                object : ProgramVmSessionFactory {
                    override fun open(artifact: ByteArray) = session
                }
            val host = ProgramRuntimeHost(factory)
            host.start(byteArrayOf())
            host.transferToFleet(fleet, 0)
            val endpoint = VmActorEndpoint(computerId, epoch)
            assertTrue(service.register(endpoint, machine, host))
            endpoints += endpoint
            return endpoint
        }

        override fun close() {
            endpoints.forEach { service.stop(it) }
            repeat(8) { service.pump(32, 32) }
            service.close()
        }
    }
}
