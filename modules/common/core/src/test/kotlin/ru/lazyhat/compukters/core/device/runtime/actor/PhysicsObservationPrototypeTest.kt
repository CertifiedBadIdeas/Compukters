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

import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Research harness for #686; deliberately not a public API or a Minecraft integration. */
class PhysicsObservationPrototypeTest {
    @Test
    fun `physics observation rate does not multiply actor turns or instruction allowances`() {
        for (substeps in listOf(1, 4, 16, 64)) {
            Prototype().use { bridge ->
                repeat(20) { tick ->
                    repeat(substeps) { bridge.publish(0.05 / substeps) }
                    assertTrue(bridge.admit(tick.toLong()))
                    assertFalse(bridge.admit(tick.toLong()))
                    val frame = bridge.awaitReply()
                    assertEquals((tick + 1L) * substeps, frame.step)
                    assertEquals(substeps - 1L, frame.skippedSteps)
                    assertEquals(0.05, frame.elapsedSeconds, 1e-10)
                }
                assertEquals(20L, bridge.scheduler.metrics().acceptedPermits)
                assertEquals(20L * ALLOWANCE, bridge.totalAllowance)
                assertEquals(0L, bridge.scheduler.metrics().permitPendingRejections)
            }
        }
    }

    @Test
    fun `busy worker keeps only newest observation without blocking the physics publisher`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Prototype { frame ->
            if (frame.step == 1L) {
                entered.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        }.use { bridge ->
            try {
                bridge.publish(0.01)
                assertTrue(bridge.admit(0))
                assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                // Executing these publications while the worker is held proves they do not await it.
                repeat(4_096) { step ->
                    bridge.publish(0.01)
                    assertFalse(bridge.admit(step + 1L))
                }
                assertEquals(4_097L, bridge.latestStep)
                assertEquals(1L, bridge.scheduler.metrics().acceptedPermits)
                assertEquals(0, bridge.scheduler.metrics().queuedMessages)
                release.countDown()
                assertEquals(1L, bridge.awaitReply().step)
                assertTrue(bridge.admit(4_097))
                val frame = bridge.awaitReply()
                assertEquals(4_097L, frame.step)
                assertEquals(4_095L, frame.skippedSteps)
                assertEquals(40.96, frame.elapsedSeconds, 1e-8)
                assertEquals(2L, bridge.scheduler.metrics().acceptedPermits)
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `rebinding rejects a reply from the previous construction`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Prototype { frame ->
            if (frame.generation == 1L) {
                entered.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        }.use { bridge ->
            try {
                bridge.publish(0.01)
                assertTrue(bridge.admit(0))
                assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                bridge.rebind()
                bridge.publish(0.02)
                assertFalse(bridge.admit(1))
                release.countDown()
                bridge.awaitReply()
                assertEquals(1, bridge.staleReplies)
                assertEquals(0, bridge.appliedReplies)
                assertTrue(bridge.admit(1))
                val current = bridge.awaitReply()
                assertEquals(2L, current.generation)
                assertEquals(0.02, current.elapsedSeconds, 1e-10)
                assertEquals(1, bridge.appliedReplies)
            } finally {
                release.countDown()
            }
        }
    }

    private data class Frame(
        val generation: Long,
        val step: Long,
        val simulationSeconds: Double,
        val skippedSteps: Long = 0,
        val elapsedSeconds: Double = 0.0,
    )

    /** Owner-thread ingress; only the immutable frame crosses into the existing worker scheduler. */
    private class Prototype(
        work: (Frame) -> Unit = {},
    ) : AutoCloseable {
        val scheduler =
            VmActorScheduler<Unit, Frame, Frame>(
                VmActorSchedulerConfig(workerCount = 1, maximumActors = 1, resultCapacityPerWorker = 2),
            )
        private val endpoint = VmActorEndpoint(ComputerId.fromLongs(686, 1), 1)
        private var generation = 1L
        private var latest: Frame? = null
        private var previous: Frame? = null
        private var inFlight = false
        private var lastAdmittedTick = -1L
        var totalAllowance = 0L
            private set
        var staleReplies = 0
            private set
        var appliedReplies = 0
            private set
        val latestStep: Long get() = latest?.step ?: 0

        init {
            check(
                scheduler.register(
                    endpoint,
                    object : VmActorProcessor<Unit, Frame, Frame> {
                        override fun process(command: Unit): Frame? = null

                        override fun advance(permit: Frame): Frame {
                            work(permit)
                            return permit
                        }
                    },
                ),
            )
        }

        fun publish(dt: Double) {
            require(dt.isFinite() && dt > 0)
            latest = Frame(generation, latestStep + 1, (latest?.simulationSeconds ?: 0.0) + dt)
        }

        fun admit(worldTick: Long): Boolean {
            val frame = latest ?: return false
            if (inFlight || worldTick <= lastAdmittedTick || previous?.step == frame.step) return false
            val delivered =
                frame.copy(
                    skippedSteps = frame.step - (previous?.step ?: 0) - 1,
                    elapsedSeconds = frame.simulationSeconds - (previous?.simulationSeconds ?: 0.0),
                )
            check(scheduler.submitWithPermit(endpoint, emptyList(), delivered) == VmActorSubmission.ACCEPTED)
            inFlight = true
            previous = frame
            lastAdmittedTick = worldTick
            totalAllowance += ALLOWANCE
            return true
        }

        fun rebind() {
            generation++
            latest = null
            previous = null
        }

        fun awaitReply(): Frame {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (System.nanoTime() < deadline) {
                val event = scheduler.drainEvents(1).firstOrNull()
                if (event != null) {
                    check(event is VmActorEvent.Result)
                    inFlight = false
                    if (event.value.generation == generation) appliedReplies++ else staleReplies++
                    return event.value
                }
                Thread.onSpinWait()
            }
            error("prototype actor reply timed out")
        }

        override fun close() = scheduler.close()
    }

    private companion object {
        const val ALLOWANCE = 131_072
        const val TIMEOUT_SECONDS = 5L
    }
}
