/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetEvent
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetStaleException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFleetOutboxTest {
    @Test
    fun `busy machine keeps its order while another machine progresses`() {
        val sent = mutableListOf<Pair<Long, Int>>()
        var busy = true
        val outbox =
            NativeFleetOutbox(
                fleet { machine, packet ->
                    if (machine == 1L && busy) throw VmFleetBusyException("full")
                    sent += machine to packet
                },
                8,
                4,
                {},
            )
        assertTrue(outbox.offer(1, listOf(input(1), input(2))))
        assertTrue(outbox.offer(2, listOf(input(3))))
        assertEquals(1, outbox.flush(3))
        assertEquals(listOf(2L to 3), sent)
        busy = false
        assertEquals(2, outbox.flush(2))
        assertEquals(listOf(2L to 3, 1L to 1, 1L to 2), sent)
    }

    @Test
    fun `capacity rejects a whole batch and is released by delivery or removal`() {
        val sent = mutableListOf<Int>()
        val outbox = NativeFleetOutbox(fleet { _, packet -> sent += packet }, 3, 2, {})
        assertTrue(outbox.offer(1, listOf(input(1))))
        assertFalse(outbox.offer(1, listOf(input(2), input(3))))
        assertTrue(outbox.offer(2, listOf(input(4), input(5))))
        assertFalse(outbox.offer(3, listOf(input(6))))
        assertEquals(listOf(input(4), input(5)), outbox.remove(2))
        assertTrue(outbox.offer(3, listOf(input(6))))
        assertEquals(2, outbox.flush(10))
        assertEquals(listOf(1, 6), sent)
    }

    @Test
    fun `permanent failure leaves the operation available for explicit cleanup`() {
        val outbox = NativeFleetOutbox(fleet { _, _ -> throw VmFleetStaleException("stale") }, 2, 2, {})
        assertTrue(outbox.offer(1, listOf(input(1), input(2))))
        val failure = assertFailsWith<NativeFleetCommandException> { outbox.flush(1) }
        assertEquals(1, failure.machine)
        assertTrue(failure.cause is VmFleetStaleException)
        assertEquals(listOf(input(1), input(2)), outbox.remove(1))
    }

    @Test
    fun `delivery respects the owner deadline`() {
        val sent = mutableListOf<Int>()
        val outbox = NativeFleetOutbox(fleet { _, packet -> sent += packet }, 2, 2, {}, { 10 })
        assertTrue(outbox.offer(1, listOf(input(1))))
        assertEquals(0, outbox.flush(10, 10))
        assertEquals(1, outbox.flush(10, 11))
        assertEquals(listOf(1), sent)
    }

    @Test
    fun `stop waits behind accepted commands and rejects subsequent work until cleanup`() {
        val sent = mutableListOf<String>()
        val fleet =
            Proxy.newProxyInstance(
                ExperimentalFleet::class.java.classLoader,
                arrayOf(ExperimentalFleet::class.java),
            ) { _, method, _ ->
                if (method.name == "pollLifecycle") {
                    ExperimentalFleetEvent.Stopped(1, emptyList(), 0)
                } else {
                    sent += method.name
                    null
                }
            } as ExperimentalFleet
        val outbox = NativeFleetOutbox(fleet, 4, 4, {})
        assertFailsWith<IllegalArgumentException> {
            outbox.offer(1, listOf(NativeFleetOutbox.Command.Stop, input(1)))
        }
        assertTrue(outbox.offer(1, listOf(input(1), NativeFleetOutbox.Command.Stop)))
        assertFailsWith<IllegalStateException> { outbox.offer(1, listOf(input(2))) }
        assertEquals(0, outbox.pumpLifecycle(1, 1) { error("not submitted") })
        assertEquals(2, outbox.flush(2))
        assertEquals(listOf("redstoneInput", "stop"), sent)
        assertFailsWith<IllegalStateException> { outbox.offer(1, listOf(input(3))) }
        assertEquals(
            1,
            outbox.pumpLifecycle(1, 1) {
                assertEquals(ExperimentalFleetEvent.Stopped(1, emptyList(), 0), it)
            },
        )
        assertTrue(outbox.offer(1, listOf(input(3))))
    }

    @Test
    fun `independent lifecycle progresses with ordinary output untouched and rotates pending owners`() {
        val probes = mutableListOf<Long>()
        val terminal = ExperimentalFleetEvent.Stopped(2, emptyList(), 0)
        val source =
            lifecycleFleet { machine ->
                probes += machine
                if (machine == 2L) terminal else null
            }
        val outbox = NativeFleetOutbox(source, 4, 4, {})
        for (machine in 1L..3L) assertTrue(outbox.offer(machine, listOf(NativeFleetOutbox.Command.Stop)))
        assertEquals(3, outbox.flush(3))
        val events = mutableListOf<ExperimentalFleetEvent>()
        assertEquals(0, outbox.pumpLifecycle(1, 1, deliver = events::add))
        assertEquals(1, outbox.pumpLifecycle(1, 1, deliver = events::add))
        assertEquals(listOf(1L, 2L), probes)
        assertEquals(listOf<ExperimentalFleetEvent>(terminal), events)
        assertTrue(outbox.offer(2, listOf(input(9))))
        assertFailsWith<IllegalStateException> { outbox.remove(1) }
        assertEquals(0, outbox.pumpLifecycle(8, 100, deliver = events::add))
        assertEquals(listOf(1L, 2L, 3L, 1L), probes)
    }

    @Test
    fun `detach prefix stays on lifecycle path and rejection releases closing admission`() {
        val prefix = ExperimentalFleetEvent.Quiescent(1, 10, 4, true)
        val rejected = ExperimentalFleetEvent.DetachRejected(1)
        val output = java.util.ArrayDeque<ExperimentalFleetEvent>(listOf(prefix, rejected))
        val outbox = NativeFleetOutbox(lifecycleFleet { output.pollFirst() }, 4, 4, {})
        assertTrue(outbox.offer(1, listOf(NativeFleetOutbox.Command.DetachWithTimers(10))))
        // A merely queued lifecycle must not yet be polled in native code.
        assertEquals(0, outbox.pumpLifecycle(1, 1) { error("not submitted") })
        assertEquals(1, outbox.flush(1))
        val events = mutableListOf<ExperimentalFleetEvent>()
        assertEquals(1, outbox.pumpLifecycle(1, 4, deliver = events::add))
        assertEquals(listOf<ExperimentalFleetEvent>(prefix), events)
        assertFailsWith<IllegalStateException> { outbox.offer(1, listOf(input(1))) }
        assertEquals(
            1,
            outbox.pumpLifecycle(4, 4) {
                events += it
                // Admission reopens before owner reconciliation, so it can submit retained work.
                assertTrue(outbox.offer(1, listOf(input(2))))
            },
        )
        assertEquals(listOf(prefix, rejected), events)
        assertEquals(listOf(input(2)), outbox.remove(1))
    }

    @Test
    fun `one detach drains its ready prefix within the event and attempt budgets`() {
        val prefix = List(5) { ExperimentalFleetEvent.Quiescent(1, 10, it.toLong(), false) }
        val terminal = ExperimentalFleetEvent.Detached(1)
        val pending = java.util.ArrayDeque<ExperimentalFleetEvent>(prefix + terminal)
        val outbox = NativeFleetOutbox(lifecycleFleet { pending.pollFirst() }, 2, 2, {})
        assertTrue(outbox.offer(1, listOf(NativeFleetOutbox.Command.DetachWithTimers(10))))
        assertEquals(1, outbox.flush(1))
        val delivered = mutableListOf<ExperimentalFleetEvent>()
        assertEquals(2, outbox.pumpLifecycle(10, 2, deliver = delivered::add))
        assertEquals(1, outbox.pumpLifecycle(1, 10, deliver = delivered::add))
        assertEquals(3, outbox.pumpLifecycle(10, 10, deliver = delivered::add))
        assertEquals(prefix + terminal, delivered)
    }

    @Test
    fun `lifecycle poll budgets and failures retain ownership without polling ordinary output`() {
        var fail = true
        var probes = 0
        val outbox =
            NativeFleetOutbox(
                lifecycleFleet {
                    probes++
                    if (fail) error("transport failure")
                    ExperimentalFleetEvent.Detached(1)
                },
                2,
                2,
                {},
                { 10 },
            )
        assertTrue(outbox.offer(1, listOf(NativeFleetOutbox.Command.DetachWithTimers(0))))
        assertEquals(1, outbox.flush(1))
        assertEquals(0, outbox.pumpLifecycle(0, 1) { error("no event budget") })
        assertEquals(0, outbox.pumpLifecycle(1, 0) { error("no attempt budget") })
        assertEquals(0, outbox.pumpLifecycle(1, 1, 10) { error("deadline") })
        assertEquals(0, probes)
        assertFailsWith<IllegalStateException> { outbox.pumpLifecycle(1, 1) {} }
        assertFailsWith<IllegalStateException> { outbox.remove(1) }
        fail = false
        val events = mutableListOf<ExperimentalFleetEvent>()
        assertEquals(1, outbox.pumpLifecycle(1, 1, 11, events::add))
        assertEquals(listOf<ExperimentalFleetEvent>(ExperimentalFleetEvent.Detached(1)), events)
        assertTrue(outbox.remove(1).isEmpty())
        assertEquals(2, probes)
    }

    private fun lifecycleFleet(poll: (Long) -> ExperimentalFleetEvent?): ExperimentalFleet =
        Proxy.newProxyInstance(
            ExperimentalFleet::class.java.classLoader,
            arrayOf(ExperimentalFleet::class.java),
        ) { _, method, args ->
            when (method.name) {
                "stop" -> {
                    assertEquals(true, args!![1])
                    null
                }

                "detachWithTimers" -> {
                    assertEquals(true, args!![2])
                    null
                }

                "pollLifecycle" -> {
                    poll(args!![0] as Long)
                }

                else -> {
                    error("unexpected method ${method.name}")
                }
            }
        } as ExperimentalFleet

    private fun input(packet: Int) = NativeFleetOutbox.Command.RedstoneInput(packet)

    private fun fleet(send: (Long, Int) -> Unit): ExperimentalFleet =
        Proxy.newProxyInstance(
            ExperimentalFleet::class.java.classLoader,
            arrayOf(ExperimentalFleet::class.java),
        ) { _, method, args ->
            check(method.name == "redstoneInput")
            send(args!![0] as Long, args[1] as Int)
            null
        } as ExperimentalFleet
}
