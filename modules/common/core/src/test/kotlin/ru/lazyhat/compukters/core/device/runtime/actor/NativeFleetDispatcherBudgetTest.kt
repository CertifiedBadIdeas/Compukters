/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetCommandBatch
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetCommandTransport
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeFleetDispatcherBudgetTest {
    @Test
    fun `continuous callbacks and commands both progress when one operation consumes a pump deadline`() {
        Source(operationNanos = 1_000).use { source ->
            repeat(8) { index ->
                source.publish(index + 1L)
                source.offer(index + 1L)
            }
            repeat(4) {
                source.dispatcher.pump(8, 8, source.now + 1_000)
            }
            assertTrue(source.sent.isNotEmpty(), "ready observations must not starve accepted commands")
            assertTrue(source.delivered.isNotEmpty(), "accepted commands must not starve ready observations")
            assertEquals(4, source.sent.size + source.delivered.size, "no second operation starts after the deadline")
            assertEquals(source.sent.sorted(), source.sent)
            assertEquals(source.delivered.sorted(), source.delivered)
        }
    }

    @Test
    fun `zero event allowance still sends commands and busy attempts consume their own allowance`() {
        Source().use { source ->
            source.publish(9)
            for (machine in 1L..3L) source.offer(machine)
            source.busy = setOf(1L)
            assertEquals(0, source.dispatcher.pump(0, 2))
            assertEquals(listOf(1L, 2L), source.attempted)
            assertEquals(listOf(2L), source.sent)
            assertEquals(0, source.polls)
            source.busy = emptySet()
            assertEquals(0, source.dispatcher.pump(0, 8))
            assertEquals(listOf(2L, 3L, 1L), source.sent)
            assertEquals(0L, source.dispatcher.outbox.retainedBytes)
            assertEquals(1, source.dispatcher.pump(1, 0))
            assertEquals(listOf(9L), source.delivered)
        }
    }

    @Test
    fun `event and transport allowances are independent and commands admitted by callbacks can use free attempts`() {
        Source().use { source ->
            source.publish(1)
            source.onDelivery = { source.offer(1) }
            assertEquals(1, source.dispatcher.pump(1, 1))
            assertEquals(listOf(1L), source.sent)
            source.onDelivery = null
            for (machine in 2L..4L) {
                source.publish(machine)
                source.offer(machine)
            }
            assertEquals(2, source.dispatcher.pump(2, 1))
            assertEquals(listOf(1L, 2L), source.sent)
            assertEquals(listOf(1L, 2L, 3L), source.delivered)
            assertEquals(1, source.dispatcher.pump(1, 2))
            assertEquals(listOf(1L, 2L, 3L, 4L), source.sent)
        }
    }

    @Test
    fun `closing from an observation fences commands admitted by that callback`() {
        Source().use { source ->
            source.publish(1)
            source.onDelivery = {
                source.offer(2)
                source.dispatcher.closeAsync()
            }
            assertEquals(1, source.dispatcher.pump(2, 2))
            assertTrue(source.sent.isEmpty())
        }
    }

    @Test
    fun `expired or closed pumps cannot submit commands even without an event allowance`() {
        Source().use { source ->
            source.offer(1)
            assertEquals(0, source.dispatcher.pump(0, 1, source.now))
            assertTrue(source.sent.isEmpty())
            source.releaseWaiter.countDown()
            source.dispatcher.close()
            assertFailsWith<IllegalStateException> { source.dispatcher.pump(0, 1) }
            assertTrue(source.sent.isEmpty())
        }
    }

    @Test
    fun `batch quantum retains event turn across a deadline and bounds command-only work`() {
        Source(operationNanos = 1_000, batched = true).use { source ->
            for (machine in 1L..20L) source.offer(machine)
            source.publish(99)
            assertEquals(0, source.dispatcher.pump(1, 32, source.now + 1_000))
            assertEquals((1L..16L).toList(), source.sent)
            assertEquals(1, source.dispatcher.pump(1, 32, source.now + 1_000))
            assertEquals(listOf(99L), source.delivered)
            assertEquals(16, source.sent.size)
            assertEquals(0, source.dispatcher.pump(0, 3))
            assertEquals((1L..19L).toList(), source.sent)
            assertEquals(64L, source.dispatcher.outbox.retainedBytes)
        }
    }

    @Test
    fun `busy batch heads consume attempts without starving other machines or observations`() {
        Source(batched = true).use { source ->
            for (machine in 1L..3L) source.offer(machine)
            source.publish(99)
            source.busy = setOf(1L)
            assertEquals(1, source.dispatcher.pump(1, 3))
            assertEquals(listOf(1L, 2L, 3L), source.attempted)
            assertEquals(listOf(2L, 3L), source.sent)
            assertEquals(listOf(99L), source.delivered)
            assertEquals(64L, source.dispatcher.outbox.retainedBytes)
            source.busy = emptySet()
            source.dispatcher.pump(0, 1)
            assertEquals(listOf(2L, 3L, 1L), source.sent)
        }
    }

    @Test
    fun `inspection preparation and its command use the same pump without sharing attempt credit`() {
        Source().use { source ->
            var pending = false
            var preparations = 0
            source.publish(1)
            source.onDelivery = { pending = true }
            source.onPrepare = {
                if (pending) {
                    pending = false
                    preparations++
                    source.offer(1)
                    1
                } else {
                    0
                }
            }
            assertEquals(1, source.dispatcher.pump(1, 1))
            assertEquals(1, preparations)
            assertEquals(listOf(1L), source.sent)
        }
    }

    @Test
    fun `bounded preparation retries cannot consume the transport attempt needed to free an outbox`() {
        Source().use { source ->
            var preparations = 0
            source.offer(1)
            source.onPrepare = {
                preparations++
                1
            }
            assertEquals(0, source.dispatcher.pump(0, 1))
            assertEquals(1, preparations)
            assertEquals(listOf(1L), source.sent)
        }
    }

    private class Source(
        private val operationNanos: Long = 0,
        batched: Boolean = false,
    ) : AutoCloseable {
        var now = 0L
        var polls = 0
        var busy: Set<Long> = emptySet()
        var onDelivery: (() -> Unit)? = null
        var onPrepare: (() -> Int)? = null
        val attempted = mutableListOf<Long>()
        val sent = mutableListOf<Long>()
        val delivered = mutableListOf<Long>()
        val releaseWaiter = CountDownLatch(1)
        private val ready = ArrayDeque<ExperimentalFleetEvent.Quiescent>()
        private val scalar =
            Proxy.newProxyInstance(ExperimentalFleet::class.java.classLoader, arrayOf(ExperimentalFleet::class.java)) { _, method, args ->
                when (method.name) {
                    "eventGeneration" -> {
                        0L
                    }

                    "waitEvents" -> {
                        check(releaseWaiter.await(5, TimeUnit.SECONDS))
                        false
                    }

                    "poll" -> {
                        polls++
                        ready.pollFirst()
                    }

                    "grantFrame" -> {
                        val machine = args!![0] as Long
                        attempted.add(machine)
                        now += operationNanos
                        if (machine in busy) throw VmFleetBusyException("full")
                        sent.add(machine)
                        Unit
                    }

                    else -> {
                        error("unexpected method ${method.name}")
                    }
                }
            } as ExperimentalFleet
        private val fleet =
            if (!batched) {
                scalar
            } else {
                object : ExperimentalFleet by scalar, ExperimentalFleetCommandTransport {
                    override fun submitCommands(batch: ExperimentalFleetCommandBatch) {
                        batch.prepare(now)
                        for (index in 0 until batch.size) {
                            val machine = batch.transportBuffer.getLong(8 + index * 40)
                            attempted += machine
                            val status = if (machine in busy) 7 else 0.also { sent += machine }
                            batch.transportBuffer.put(ExperimentalFleetCommandBatch.INPUT_BYTES + index, status.toByte())
                        }
                        now += operationNanos
                    }
                }
            }
        val dispatcher =
            NativeFleetDispatcher(
                fleet,
                32,
                4,
                {},
                {},
                {
                    delivered.add((it as ExperimentalFleetEvent.Quiescent).machine)
                    now += operationNanos
                    onDelivery?.invoke()
                },
                nanoTime = { now },
                prepareCommands = { _, _ -> onPrepare?.invoke() ?: 0 },
            )

        fun publish(machine: Long) {
            ready.addLast(ExperimentalFleetEvent.Quiescent(machine, 1, 0, false))
        }

        fun offer(machine: Long) {
            assertTrue(
                dispatcher.outbox.offer(machine, listOf(NativeFleetOutbox.Command.FrameGrant(1, 4096, Long.MAX_VALUE, Long.MAX_VALUE))),
            )
        }

        override fun close() {
            releaseWaiter.countDown()
            dispatcher.close()
        }
    }
}
