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
                sent += method.name
                null
            } as ExperimentalFleet
        val outbox = NativeFleetOutbox(fleet, 4, 4, {})
        assertFailsWith<IllegalArgumentException> {
            outbox.offer(1, listOf(NativeFleetOutbox.Command.Stop, input(1)))
        }
        assertTrue(outbox.offer(1, listOf(input(1), NativeFleetOutbox.Command.Stop)))
        assertFailsWith<IllegalStateException> { outbox.offer(1, listOf(input(2))) }
        val stopped = ExperimentalFleetEvent.Stopped(1, emptyList())
        assertFailsWith<IllegalStateException> { outbox.acknowledgeLifecycle(stopped) }
        assertEquals(2, outbox.flush(2))
        assertEquals(listOf("redstoneInput", "stop"), sent)
        assertFailsWith<IllegalStateException> { outbox.offer(1, listOf(input(3))) }
        assertFailsWith<IllegalStateException> { outbox.acknowledgeLifecycle(ExperimentalFleetEvent.DetachRejected(1)) }
        outbox.acknowledgeLifecycle(stopped)
        assertTrue(outbox.offer(1, listOf(input(3))))
    }

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
