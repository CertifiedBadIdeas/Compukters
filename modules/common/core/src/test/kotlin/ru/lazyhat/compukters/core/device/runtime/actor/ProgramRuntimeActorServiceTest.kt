/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramRuntimeActorServiceTest {
    @Test
    fun `direct turns complete on owner delivery and may submit another request`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(10, 1), 1)
        val owner = Thread.currentThread()
        ProgramRuntimeActorService(testConfig()).use { service ->
            assertTrue(service.registerStandalone(endpoint))
            var delivered = 0
            var followup: CompletableFuture<ProgramRuntimeActorReply>? = null
            assertEquals(
                VmActorSubmission.ACCEPTED,
                service.submitTurn(endpoint, 0, emptyList()) { reply, failure ->
                    assertEquals(owner, Thread.currentThread())
                    assertNull(failure)
                    assertEquals(ProgramRuntimeState.Idle, requireNotNull(reply).state)
                    assertEquals(0, service.runtimeMetrics().pendingRequests)
                    delivered++
                    followup = service.request(endpoint, ProgramRuntimeActorCommand::TerminalFullState)
                },
            )
            awaitQueuedResult(service)
            assertEquals(0, delivered)
            assertFalse(service.isQuiescent())
            assertEquals(1, service.pump(1))
            assertEquals(1, delivered)
            assertFalse(requireNotNull(followup).isDone)
            awaitQueuedResult(service)
            assertEquals(1, service.pump(1))
            assertTrue(requireNotNull(followup).isDone)
            assertEquals(0, service.runtimeMetrics().pendingRequests)
        }
    }

    @Test
    fun `direct admission refusal never invokes completion or retains a pending request`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(10, 2), 1)
        ProgramRuntimeActorService(testConfig(), capacityFramesRequired = true).use { service ->
            var completions = 0
            val completion = ProgramRuntimeTurnCompletion { _, _ -> completions++ }
            assertEquals(VmActorSubmission.STALE_ENDPOINT, service.submitTurn(endpoint, 0, emptyList(), completion))
            assertTrue(service.isQuiescent())
            assertTrue(service.registerStandalone(endpoint))
            service.beginCapacityFrame(0)
            assertEquals(VmActorSubmission.ACCEPTED, service.submitTurn(endpoint, 0, emptyList(), completion))
            assertEquals(VmActorSubmission.PERMIT_PENDING, service.submitTurn(endpoint, 0, emptyList(), completion))
            assertEquals(1, service.runtimeMetrics().pendingRequests)
            assertFalse(service.continueTurn(endpoint, 0, emptyList(), completion))
            assertEquals(0, completions)
            service.flushCapacityFrame()
            awaitQueuedResult(service)
            service.pump(1)
            assertEquals(1, completions)
            assertEquals(0, service.runtimeMetrics().pendingRequests)
        }
    }

    @Test
    fun `owner pump checks its deadline between direct callbacks`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(10, 3), 1)
        var now = 0L
        ProgramRuntimeActorService(testConfig(), nanoTime = { now }).use { service ->
            assertTrue(service.registerStandalone(endpoint))
            var delivered = 0
            assertEquals(
                VmActorSubmission.ACCEPTED,
                service.submitTurn(endpoint, 0, emptyList()) { _, _ ->
                    delivered++
                    now = 100
                },
            )
            awaitQueuedResult(service)
            assertEquals(VmActorSubmission.ACCEPTED, service.submitTurn(endpoint, 1, emptyList()) { _, _ -> delivered++ })
            awaitQueuedResults(service, 2)
            assertEquals(0, service.pump(0))
            assertEquals(0, service.pump(2, 0))
            assertEquals(1, service.pump(2, 50))
            assertEquals(1, delivered)
            assertEquals(1, service.runtimeMetrics().pendingRequests)
            assertEquals(1, service.pump(1))
            assertEquals(2, delivered)
        }
    }

    @Test
    fun `a throwing direct callback cannot stop other delivery or shutdown cleanup`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(10, 4), 1)
        ProgramRuntimeActorService(testConfig()).use { service ->
            assertTrue(service.registerStandalone(endpoint))
            service.submitTurn(endpoint, 0, emptyList()) { _, _ -> error("deliberate callback failure") }
            awaitQueuedResult(service)
            var replies = 0
            service.submitTurn(endpoint, 1, emptyList()) { _, failure ->
                assertNull(failure)
                replies++
            }
            awaitQueuedResults(service, 2)
            assertEquals(2, service.pump(2))
            assertEquals(1, replies)
            assertEquals(0, service.runtimeMetrics().pendingRequests)
            var closeFailure: Throwable? = null
            service.submitTurn(endpoint, 2, emptyList()) { _, failure ->
                closeFailure = failure
                error("deliberate close callback failure")
            }
            awaitQueuedResult(service)
            val future = service.request(endpoint, ProgramRuntimeActorCommand::TerminalFullState)
            awaitQueuedResults(service, 2)
            service.close()
            assertIs<ProgramRuntimeActorServiceClosedException>(closeFailure)
            assertTrue(future.isCompletedExceptionally)
            assertEquals(0, service.runtimeMetrics().pendingRequests)
        }
    }

    @Test
    fun `cancelled future turns leave native work to drain without completing another turn`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(10, 5), 1)
        ProgramRuntimeActorService(testConfig()).use { service ->
            assertTrue(service.registerStandalone(endpoint))
            val cancelled = service.turn(endpoint, 0)
            awaitQueuedResult(service)
            assertTrue(cancelled.cancel(false))
            assertEquals(0, service.runtimeMetrics().pendingRequests)
            assertFalse(service.isQuiescent())
            var replies = 0
            service.submitTurn(endpoint, 1, emptyList()) { _, failure ->
                assertNull(failure)
                replies++
            }
            awaitQueuedResults(service, 2)
            assertEquals(2, service.pump(2))
            assertEquals(1, replies)
            assertTrue(cancelled.isCancelled)
        }
    }

    @Test
    fun `capacity frame divides one reservation among submitted computers`() {
        val first = VmActorEndpoint(ComputerId.fromLongs(0, 1), 1)
        val second = VmActorEndpoint(ComputerId.fromLongs(0, 2), 1)
        val config = VmActorSchedulerConfig(workerCount = 1, maximumActors = 2, mailboxCapacity = 4, messagesPerTurn = 1)
        ProgramRuntimeActorService(config, perComputerEntitlement = 2, safeInstructionCapacity = 1).use { service ->
            assertTrue(service.registerStandalone(first))
            assertTrue(service.registerStandalone(second))
            service.beginCapacityFrame(0)
            val firstTurn = service.turn(first, 0)
            val secondTurn = service.turn(second, 0)
            assertEquals(0, service.metrics().processedPermits)

            service.flushCapacityFrame()
            assertEquals(listOf(1L, 0L), service.lastCapacityAllocation.grants.map { it.retiredInstructionLimit })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while ((!firstTurn.isDone || !secondTurn.isDone) && System.nanoTime() < deadline) {
                service.pump(2)
                Thread.onSpinWait()
            }
            assertTrue(firstTurn.isDone)
            assertTrue(secondTurn.isDone)
            assertEquals(2, service.metrics().processedPermits)

            service.beginCapacityFrame(1)
            assertEquals(1L, service.runtimeMetrics().reservedInstructionsLastFrame)
            service.turn(first, 1, listOf(ProgramRuntimeActorEffect.RedstoneInput(0)))
            service.turn(second, 1)
            service.flushCapacityFrame()
            assertEquals(listOf(first), service.lastCapacityAllocation.grants.map { it.endpoint })
        }
    }

    @Test
    fun `accepted request completes only when the server pumps its result`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(1, 2), 1)
        ProgramRuntimeActorService(testConfig()).use { service ->
            assertTrue(service.registerStandalone(endpoint))

            val future =
                service.request(endpoint) { requestId ->
                    ProgramRuntimeActorCommand.TerminalFullState(requestId)
                }
            awaitQueuedResult(service)

            assertFalse(future.isDone)
            assertEquals(1, service.pump(1))
            val reply = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNull(assertIs<ProgramRuntimeActorValue.TerminalStateValue>(reply.value).state)
            val metrics = service.runtimeMetrics()
            assertEquals(1, metrics.scheduler.drainedEvents)
            assertTrue(metrics.scheduler.totalResultLatencyNanos > 0)
            assertTrue(metrics.scheduler.maximumResultLatencyNanos > 0)
            assertEquals(1, metrics.lastPumpEvents)
            assertTrue(metrics.lastPumpNanos > 0)
        }
    }

    @Test
    fun `stale endpoint fails without waiting for a result`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(3, 4), 1)
        ProgramRuntimeActorService(testConfig()).use { service ->
            val future =
                service.request(endpoint) { requestId ->
                    ProgramRuntimeActorCommand.TerminalFullState(requestId)
                }

            val failure =
                assertFailsWith<ExecutionException> {
                    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            assertEquals(
                VmActorSubmission.STALE_ENDPOINT,
                assertIs<ProgramRuntimeActorRequestException>(failure.cause).submission,
            )

            val rejectedInput =
                service.request(endpoint) { requestId ->
                    ProgramRuntimeActorCommand.SendTerminalText(requestId, "input")
                }
            assertFailsWith<ExecutionException> {
                rejectedInput.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            assertEquals(1, service.runtimeMetrics().rejectedInputRequests)
            assertEquals(2, service.metrics().staleEndpointRejections)
        }
    }

    @Test
    fun `command factory must preserve the allocated request id`() {
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(5, 6), 1)
        ProgramRuntimeActorService(testConfig()).use { service ->
            assertFailsWith<IllegalArgumentException> {
                service.request(endpoint) {
                    ProgramRuntimeActorCommand.TerminalFullState(ProgramRuntimeRequestId(it.value + 1))
                }
            }
        }
    }

    private fun awaitQueuedResult(service: ProgramRuntimeActorService) {
        awaitQueuedResults(service, 1)
    }

    private fun awaitQueuedResults(
        service: ProgramRuntimeActorService,
        count: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (service.metrics().queuedResults < count && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(service.metrics().queuedResults >= count, "runtime actor result was not queued")
    }

    private fun testConfig(): VmActorSchedulerConfig =
        VmActorSchedulerConfig(
            workerCount = 1,
            maximumActors = 1,
            mailboxCapacity = 4,
            messagesPerTurn = 1,
            resultCapacityPerWorker = 4,
        )

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
