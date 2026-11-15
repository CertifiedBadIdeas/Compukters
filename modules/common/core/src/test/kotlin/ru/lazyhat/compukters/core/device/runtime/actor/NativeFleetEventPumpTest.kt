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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class NativeFleetEventPumpTest {
    @Test
    fun `notifications wake owner while event delivery obeys its budget`() {
        val source = FleetSource()
        val owner = Thread.currentThread()
        val notifications = LinkedBlockingQueue<Unit>()
        val delivered = mutableListOf<ExperimentalFleetEvent>()
        val first = ExperimentalFleetEvent.Quiescent(1, 2, 3, false)
        val second = ExperimentalFleetEvent.Quiescent(2, 2, 4, false)
        source.publish(first)
        NativeFleetEventPump(source.fleet, { check(Thread.currentThread() === owner) }, { notifications.offer(Unit) }, delivered::add).use { pump ->
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
            val result = java.util.concurrent.CompletableFuture.supplyAsync { runCatching { pump.pump(1) }.exceptionOrNull() }
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
        val fleet: ExperimentalFleet = Proxy.newProxyInstance(
            ExperimentalFleet::class.java.classLoader,
            arrayOf(ExperimentalFleet::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "eventGeneration" -> synchronized(lock) { generation }
                "poll" -> synchronized(lock) { events.pollFirst() }
                "waitEvents" -> synchronized(lock) {
                    val expected = arguments!![0] as Long
                    if (generation == expected) lock.wait((arguments[1] as Int).toLong())
                    generation != expected
                }
                else -> error("unexpected method ${method.name}")
            }
        } as ExperimentalFleet

        fun publish(event: ExperimentalFleetEvent) = synchronized(lock) {
            events.addLast(event)
            generation++
            lock.notifyAll()
        }
    }
}
