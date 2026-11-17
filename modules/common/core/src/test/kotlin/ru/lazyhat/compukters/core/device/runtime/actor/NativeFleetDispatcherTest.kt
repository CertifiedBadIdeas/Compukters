/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFleetDispatcherTest {
    @Test
    fun `outbound grants wake owner before next tick and admission coalesces notifications`() {
        val waiterEntered = CountDownLatch(1)
        val releaseWaiter = CountDownLatch(1)
        val notifications = AtomicInteger()
        var now = 0L
        var busy = false
        val sent = mutableListOf<Long>()
        val fleet =
            Proxy.newProxyInstance(ExperimentalFleet::class.java.classLoader, arrayOf(ExperimentalFleet::class.java)) { _, method, args ->
                when (method.name) {
                    "eventGeneration" -> {
                        0L
                    }

                    "waitEvents" -> {
                        waiterEntered.countDown()
                        check(releaseWaiter.await(5, TimeUnit.SECONDS))
                        false
                    }

                    "poll" -> {
                        null
                    }

                    "grant" -> {
                        if (busy) throw VmFleetBusyException("full")
                        assertTrue(now < args!![3] as Long, "grant must arrive before its deadline")
                        sent += args[0] as Long
                        Unit
                    }

                    else -> {
                        error("unexpected method ${method.name}")
                    }
                }
            } as ExperimentalFleet
        val dispatcher = NativeFleetDispatcher(fleet, 4, 2, {}, { notifications.incrementAndGet() }, {})
        try {
            assertTrue(waiterEntered.await(5, TimeUnit.SECONDS))
            notifications.set(0) // Initial waiter notification has finished; no native events will arrive.
            val grant = NativeFleetOutbox.Command.Grant(1, 4096, 2_500_000)
            assertTrue(dispatcher.outbox.offer(1, listOf(grant)))
            assertTrue(dispatcher.outbox.offer(2, listOf(grant)))
            assertEquals(1, notifications.get(), "accepted commands must schedule owner work without a native event")
            assertTrue(sent.isEmpty(), "notification must not execute native work inline")
            dispatcher.pump(0, 1)
            assertEquals(listOf(1L), sent)
            assertTrue(dispatcher.outbox.offer(3, listOf(grant)))
            assertEquals(2, notifications.get(), "new work must wake owner even with an older queue still pending")
            dispatcher.pump(0, 4)
            assertEquals(listOf(1L, 2L, 3L), sent)
            now += 50_000_000 // All grants were sent before the next 20 TPS tick.
            busy = true
            assertTrue(dispatcher.outbox.offer(4, listOf(grant)))
            dispatcher.pump(0, 4)
            assertEquals(3, notifications.get(), "busy retries must not schedule a spinning owner loop")
            assertTrue(dispatcher.outbox.offer(4, listOf(grant)))
            assertEquals(4, notifications.get())
            assertFalse(dispatcher.outbox.offer(4, listOf(grant)))
            assertTrue(dispatcher.outbox.offer(5, emptyList()))
            assertEquals(4, notifications.get(), "rejected and empty batches must not wake owner")
            dispatcher.outbox.remove(4)
            dispatcher.pump(0, 0)
            assertTrue(dispatcher.outbox.offer(6, listOf(grant)))
            assertEquals(5, notifications.get(), "a consumed wakeup must rearm even when its queue was fenced")
        } finally {
            releaseWaiter.countDown()
            dispatcher.close()
        }
    }
}
