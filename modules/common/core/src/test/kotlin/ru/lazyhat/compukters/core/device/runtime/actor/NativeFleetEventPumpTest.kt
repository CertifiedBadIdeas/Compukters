/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeFleetEventPumpTest {
    @Test
    fun `event notifications coalesce until owner has observation allowance while command wakeups remain independent`() {
        val source = FleetSource()
        val notifications = LinkedBlockingQueue<Unit>()
        val delivered = mutableListOf<ExperimentalFleetEvent>()
        NativeFleetEventPump(source.fleet, {}, { notifications.offer(Unit) }, delivered::add).use { pump ->
            assertNotNull(notifications.poll(5, TimeUnit.SECONDS))
            source.publish(ExperimentalFleetEvent.Quiescent(1, 1, 0, false))
            source.awaitObservation()
            assertNull(notifications.poll(), "publication must coalesce with the pending initial notification")
            assertEquals(0, pump.pump(0))
            source.publish(ExperimentalFleetEvent.Quiescent(2, 1, 0, false))
            source.awaitObservation()
            assertNull(notifications.poll(), "zero event allowance must not rearm result notifications")
            pump.requestPump()
            assertNotNull(notifications.poll(), "outbound work must still wake the owner independently")
            assertEquals(1, pump.pump(1))
            source.publish(ExperimentalFleetEvent.Quiescent(3, 1, 0, false))
            source.awaitObservation()
            assertNotNull(notifications.poll(), "reading with observation allowance must rearm readiness")
            assertEquals(2, pump.pump(8))
            assertEquals(listOf(1L, 2L, 3L), delivered.map { (it as ExperimentalFleetEvent.Quiescent).machine })
        }
    }

    @Test
    fun `asynchronous close waits for native wait to end without blocking owner`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fleet =
            Proxy.newProxyInstance(
                ExperimentalFleet::class.java.classLoader,
                arrayOf(ExperimentalFleet::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "eventGeneration" -> {
                        0L
                    }

                    "waitEvents" -> {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        false
                    }

                    else -> {
                        error("unexpected method ${method.name}")
                    }
                }
            } as ExperimentalFleet
        val pump = NativeFleetEventPump(fleet, {}, {}, {})
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val completion = pump.closeAsync()
            assertFalse(completion.isDone)
            assertFailsWith<IllegalStateException> { pump.pump(1) }
            release.countDown()
            completion.get(5, TimeUnit.SECONDS)
            assertTrue(pump.closeAsync().isDone)
        } finally {
            release.countDown()
            pump.close()
        }
    }

    @Test
    fun `notifications wake owner while event delivery obeys its budget`() {
        val source = FleetSource()
        val owner = Thread.currentThread()
        val notifications = LinkedBlockingQueue<Unit>()
        val delivered = mutableListOf<ExperimentalFleetEvent>()
        val first = ExperimentalFleetEvent.Quiescent(1, 2, 3, false)
        val second = ExperimentalFleetEvent.Quiescent(2, 2, 4, false)
        source.publish(first)
        NativeFleetEventPump(
            source.fleet,
            { check(Thread.currentThread() === owner) },
            { notifications.offer(Unit) },
            delivered::add,
        ).use { pump ->
            assertNotNull(notifications.poll(5, TimeUnit.SECONDS))
            assertEquals(1, pump.pump(1))
            source.publish(second)
            assertNotNull(notifications.poll(5, TimeUnit.SECONDS))
            assertEquals(0, pump.pump(0))
            assertEquals(1, pump.pump(10))
            assertEquals(listOf<ExperimentalFleetEvent>(first, second), delivered)
        }
    }

    @Test
    fun `event delivery is restricted to the owner thread`() {
        val source = FleetSource()
        val owner = Thread.currentThread()
        NativeFleetEventPump(source.fleet, { check(Thread.currentThread() === owner) }, {}, {}).use { pump ->
            val result =
                java.util.concurrent.CompletableFuture
                    .supplyAsync { runCatching { pump.pump(1) }.exceptionOrNull() }
            assertEquals(IllegalStateException::class, result.get(5, TimeUnit.SECONDS)!!::class)
        }
    }

    @Test
    fun `closed pump rejects further delivery`() {
        val source = FleetSource()
        val pump = NativeFleetEventPump(source.fleet, {}, {}, {})
        pump.close()
        assertFailsWith<IllegalStateException> { pump.pump(1) }
    }

    private class FleetSource {
        private val lock = Object()
        private val events = ArrayDeque<ExperimentalFleetEvent>()
        private var generation = 0L
        private var observedGeneration = -1L
        val fleet: ExperimentalFleet =
            Proxy.newProxyInstance(
                ExperimentalFleet::class.java.classLoader,
                arrayOf(ExperimentalFleet::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "eventGeneration" -> {
                        synchronized(lock) { generation }
                    }

                    "poll" -> {
                        synchronized(lock) { events.pollFirst() }
                    }

                    "waitEvents" -> {
                        synchronized(lock) {
                            val expected = arguments!![0] as Long
                            observedGeneration = expected
                            lock.notifyAll()
                            if (generation == expected) lock.wait((arguments[1] as Int).toLong())
                            generation != expected
                        }
                    }

                    else -> {
                        error("unexpected method ${method.name}")
                    }
                }
            } as ExperimentalFleet

        fun publish(event: ExperimentalFleetEvent) =
            synchronized(lock) {
                events.addLast(event)
                generation++
                lock.notifyAll()
            }

        fun awaitObservation() =
            synchronized(lock) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (observedGeneration < generation) {
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) { "waiter did not observe publication" }
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                }
            }
    }
}
