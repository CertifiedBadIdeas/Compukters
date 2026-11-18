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

package ru.lazyhat.compukters.impl.computer

import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorCommand
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeActorMetrics
import ru.lazyhat.compukters.core.device.runtime.actor.ProgramRuntimeLifecycle
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorEndpoint
import ru.lazyhat.compukters.core.device.runtime.actor.VmActorSchedulerConfig
import ru.lazyhat.compukters.core.device.runtime.actor.VmCapacityCalibration
import ru.lazyhat.compukters.core.device.runtime.actor.VmCapacityGovernor
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VmActorServiceRegistryTest {
    @Test
    fun `owner wakeup stops at the shared time allowance between replies`() {
        val queued = ConcurrentLinkedQueue<Runnable>()
        var now = 0L
        val registry =
            VmActorServiceRegistry<Any, FakeRuntime>(
                checkOwner = {},
                opener = { ready ->
                    FakeRuntime(
                        ready = ready,
                        nanoTime = { now },
                    )
                },
                maximumEventsPerTick = 4,
                maximumPumpNanosPerTick = 1_000,
                nanoTime = { now },
                enqueueOnOwner = { _, task -> queued.add(task) },
            )
        val server = Any()
        registry.start(server)
        registry.tick(server, 0)
        val runtime = assertIs<FakeRuntime>(registry.service(server))
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(11, 12), 1)
        val first = runtime.request()
        first.thenRun { now += 1_000 }
        val second = runtime.request()
        awaitResults(runtime, 2)
        queued.remove().run()
        assertTrue(first.isDone)
        assertFalse(second.isDone)
        registry.afterTick(server)
        registry.tick(server, 1)
        assertTrue(second.isDone)
        registry.afterTick(server)
        registry.stop(server)
    }

    @Test
    fun `worker wakeups coalesce and owner pump shares the tick event budget`() {
        val owner = Thread.currentThread()
        val queued = ConcurrentLinkedQueue<Runnable>()
        val registry =
            VmActorServiceRegistry<Any, FakeRuntime>(
                checkOwner = { check(Thread.currentThread() === owner) },
                opener = ::service,
                maximumEventsPerTick = 1,
                maximumPumpNanosPerTick = TimeUnit.SECONDS.toNanos(5),
                enqueueOnOwner = { _, task -> queued.add(task) },
            )
        val server = Any()
        registry.start(server)
        registry.tick(server, 0)
        val runtime = assertIs<FakeRuntime>(registry.service(server))
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(7, 8), 1)
        val first = runtime.request()
        val second = runtime.request()
        awaitResults(runtime, 2)
        assertEquals(1, queued.size)
        queued.remove().run()
        assertTrue(first.isDone)
        assertFalse(second.isDone)
        assertEquals(1, runtime.queuedResults)
        registry.afterTick(server)
        registry.tick(server, 1)
        assertTrue(second.isDone)
        registry.afterTick(server)
        registry.stop(server)
    }

    @Test
    fun `queued wakeup cannot reopen a stopped or replacement service`() {
        val queued = ConcurrentLinkedQueue<Runnable>()
        val wakeupScheduled = CountDownLatch(1)
        var opened = 0
        val registry =
            VmActorServiceRegistry<Any, FakeRuntime>(
                checkOwner = {},
                opener = { ready ->
                    opened++
                    service(ready)
                },
                enqueueOnOwner = { _, task ->
                    queued.add(task)
                    wakeupScheduled.countDown()
                },
                maximumPumpNanosPerTick = TimeUnit.SECONDS.toNanos(5),
            )
        val server = Any()
        registry.start(server)
        registry.tick(server, 0)
        val runtime = assertIs<FakeRuntime>(registry.service(server))
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(9, 10), 1)
        runtime.request()
        awaitResults(runtime, 1)
        assertTrue(wakeupScheduled.await(5, TimeUnit.SECONDS), "worker must schedule the owner wakeup")
        assertEquals(1, queued.size)
        registry.afterTick(server)
        registry.stop(server)
        registry.start(server)
        queued.remove().run()
        assertEquals(1, opened)
        assertNull(registry.metrics(server))
        registry.stop(server)
    }

    @Test
    fun `completed startup calibration replaces fallback and failure keeps it bounded`() {
        val successful =
            VmActorServiceRegistry<Any, FakeRuntime>(
                checkOwner = {},
                opener = ::service,
                calibrator = { CompletableFuture.completedFuture(VmCapacityCalibration(1_000_000, 100_000_000, 2)) },
            )
        val failed =
            VmActorServiceRegistry<Any, FakeRuntime>(
                checkOwner = {},
                opener = ::service,
                calibrator = { CompletableFuture.failedFuture(IllegalStateException("sample failed")) },
            )
        val first = Any()
        val second = Any()
        successful.start(first)
        failed.start(second)
        try {
            val calibrated = successful.service(first)
            val fallback = failed.service(second)
            successful.tick(first)
            failed.tick(second)
            assertEquals(12_500L, calibrated.currentSafeCapacity())
            assertNull(calibrated.capacityFallbackReason())
            assertEquals(8_192L, fallback.currentSafeCapacity())
            assertEquals("sample failed", fallback.capacityFallbackReason())
        } finally {
            successful.stop(first)
            failed.stop(second)
        }
    }

    @Test
    fun `idle server ticks allocate no worker service and stop forbids reopening`() {
        var opened = 0
        val registry =
            VmActorServiceRegistry<Any, FakeRuntime>({}, {
                opened++
                service(it)
            })
        val server = Any()
        registry.start(server)
        assertNull(registry.metrics(server))
        repeat(10) { assertEquals(0, registry.tick(server)) }
        assertEquals(0, opened)
        registry.stop(server)
        registry.stop(server)
        assertFailsWith<IllegalStateException> { registry.service(server) }
        assertEquals(0, opened)
    }

    @Test
    fun `each server owns one service and ticks deliver at most the configured budget`() {
        val owner = Thread.currentThread()
        val registry =
            VmActorServiceRegistry<Any, FakeRuntime>(
                { check(Thread.currentThread() === owner) },
                ::service,
                maximumEventsPerTick = 1,
            )
        val first = Any()
        val second = Any()
        registry.start(first)
        registry.start(second)
        try {
            val runtime = assertIs<FakeRuntime>(registry.service(first))
            assertSame(runtime, registry.service(first))
            assertEquals(runtime.runtimeMetrics(), registry.metrics(first))
            val endpoint = VmActorEndpoint(ComputerId.fromLongs(1, 1), 1)
            val firstReply = runtime.request()
            val secondReply = runtime.request()
            var callbackThread: Thread? = null
            firstReply.thenRun { callbackThread = Thread.currentThread() }
            awaitResults(runtime, 2)
            assertFalse(firstReply.isDone)
            assertEquals(0, registry.tick(second))
            assertEquals(1, registry.tick(first))
            assertTrue(firstReply.isDone)
            assertFalse(secondReply.isDone)
            assertSame(owner, callbackThread)
            assertEquals(1, registry.tick(first))
            assertTrue(secondReply.isDone)
        } finally {
            registry.stop(first)
            registry.stop(second)
        }
        assertFailsWith<IllegalStateException> { registry.service(first) }
    }

    @Test
    fun `stop completes pending requests and rejects callback reopening`() {
        val registry = VmActorServiceRegistry<Any, FakeRuntime>({}, ::service)
        val server = Any()
        registry.start(server)
        val runtime = assertIs<FakeRuntime>(registry.service(server))
        val endpoint = VmActorEndpoint(ComputerId.fromLongs(2, 2), 1)
        val reply = runtime.request()
        awaitResults(runtime, 1)
        var reopenRejected = false
        reply.whenComplete { _, _ ->
            reopenRejected = runCatching { registry.service(server) }.isFailure
        }
        registry.stop(server)
        assertTrue(reply.isCompletedExceptionally)
        assertTrue(reopenRejected)
        assertEquals(0, if (runtime.closed) 0 else 1)
    }

    private fun service(ready: () -> Unit = {}) = FakeRuntime(ready)

    private fun awaitResults(
        service: FakeRuntime,
        count: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (service.queuedResults < count && System.nanoTime() < deadline) Thread.onSpinWait()
        assertEquals(count, service.queuedResults)
    }

    /** Ready-result test double: no threads, actors, native sessions or Guest execution. */
    private class FakeRuntime(
        private val ready: () -> Unit,
        private val nanoTime: () -> Long = System::nanoTime,
    ) : ProgramRuntimeLifecycle {
        private val governor = VmCapacityGovernor()
        private val results = ArrayDeque<CompletableFuture<Unit>>()
        var closed = false
            private set
        val queuedResults: Int get() = results.size

        fun request(): CompletableFuture<Unit> =
            CompletableFuture<Unit>().also {
                check(!closed)
                results.addLast(it)
                ready()
            }

        override fun beginCapacityFrame(worldTick: Long) = Unit

        override fun flushCapacityFrame() = Unit

        override fun observePreviousCapacityFrame() = Unit

        override fun pump(
            maximumEvents: Int,
            deadlineNanos: Long,
        ): Int {
            var delivered = 0
            while (delivered < maximumEvents && results.isNotEmpty() && (deadlineNanos == Long.MAX_VALUE || nanoTime() < deadlineNanos)) {
                results.removeFirst().complete(Unit)
                delivered++
            }
            return delivered
        }

        override fun runtimeMetrics() =
            ProgramRuntimeActorMetrics(
                pendingRequests = results.size,
                deferredWorldRequests = 0,
                totalDeferredWorldRequests = 0,
                hostContinuationSamples = 0,
                totalHostContinuationDelayTicks = 0,
                maximumHostContinuationDelayTicks = 0,
                rejectedInputRequests = 0,
                coalescedRedstoneInputs = 0,
                lastPumpEvents = 0,
                lastPumpNanos = 0,
            )

        override fun calibratedCapacity() = governor.calibratedCapacity

        override fun currentSafeCapacity() = governor.currentCapacity

        override fun capacityFallbackReason() = governor.fallbackReason

        override fun acceptCapacityCalibration(measurement: VmCapacityCalibration) = governor.calibrated(measurement)

        override fun rejectCapacityCalibration(reason: String) = governor.calibrationFailed(reason)

        override fun close() {
            closed = true
            while (results.isNotEmpty()) results.removeFirst().completeExceptionally(IllegalStateException("closed"))
        }
    }
}
