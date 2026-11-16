/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.api.addon.ProgramAddonRequest
import ru.lazyhat.compukters.api.commands.CommandBatchCodec
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.VmFleetBusyException
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequestIdentity
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeFleetCommandRequestsTest {
    @Test
    fun `per-machine eviction preserves other machines and byte eviction stays within its allowance`() {
        val local = Harness(entries = 4, perMachine = 1)
        val first = packet(1, 2)
        val other = packet(1, 3)
        val replacement = packet(2, 1)
        local.requests.inspect(1, first)
        local.drain()
        local.requests.inspect(2, other)
        local.drain()
        local.requests.inspect(1, replacement)
        assertNull(local.requests.take(1, first.identity, 0))
        assertSame(other, local.requests.take(2, other.identity, 0))
        assertSame(replacement, local.requests.take(1, replacement.identity, 0))
        assertEquals(0L, local.requests.retainedBytes)

        val bounded = Harness(bytes = 1500)
        bounded.requests.inspect(1, first)
        bounded.drain()
        bounded.requests.inspect(2, first)
        assertTrue(bounded.requests.retainedBytes in 1..1500)
        assertNull(bounded.requests.take(1, first.identity, 0))
        assertSame(first, bounded.requests.take(2, first.identity, 0))
        assertEquals(0L, bounded.requests.retainedBytes)
    }

    @Test
    fun `cost retries under both queue pressures and dispatch reuses the immutable decode`() {
        val h = Harness(entries = 1)
        val request = packet(1, 3)
        val decoded = CommandBatchCodec.decode(request)
        assertTrue(h.outbox.offer(1, listOf(NativeFleetOutbox.Command.RedstoneInput(0))))
        h.requests.inspect(1, request)
        h.requests.pump(1, Long.MAX_VALUE) // JVM outbox full: retain only scalar classification.
        assertTrue(h.costs.isEmpty())
        h.outbox.flush(1)
        h.requests.pump(1, Long.MAX_VALUE)
        h.busy = true
        repeat(3) { h.outbox.flush(1) }
        assertTrue(h.costs.isEmpty())
        h.busy = false
        h.outbox.flush(1)
        assertEquals(listOf(Triple(1L, request.identity, 3)), h.costs)
        val prepared = h.requests.take(1, request.identity, 0)!!
        assertSame(decoded, CommandBatchCodec.decode(prepared))
        assertNull(h.requests.take(1, request.identity, 0))
        assertEquals(0L, h.requests.retainedBytes)
    }

    @Test
    fun `eviction and scalar-only limits never lose classifications`() {
        val h = Harness(entries = 1)
        val first = packet(1, 2)
        val second = packet(2, 3)
        h.requests.inspect(1, first)
        h.requests.inspect(2, second)
        assertNull(h.requests.take(1, first.identity, 0))
        assertSame(second, h.requests.take(2, second.identity, 0))
        h.drain()
        assertEquals(listOf(2, 3), h.costs.map { it.third })
        val scalar = Harness(bytes = 64)
        scalar.requests.inspect(1, first)
        assertEquals(0L, scalar.requests.retainedBytes)
        scalar.drain()
        assertEquals(2, scalar.costs.single().third)
    }

    @Test
    fun `malformed packets cost one without retaining their payload`() {
        for (arguments in listOf(emptyList(), listOf(VmValue.I32(7)), listOf(VmValue.ByteArrayValue(byteArrayOf(2, 32))))) {
            val h = Harness()
            val request = ProgramAddonRequest(VmHostRequestIdentity(1, 1), NativeFleetCommandRequests.INSPECTION.capability, 0, arguments)
            h.requests.inspect(1, request)
            h.drain()
            assertEquals(1, h.costs.single().third)
            assertNull(h.requests.take(1, request.identity, 0))
            assertEquals(0L, h.requests.retainedBytes)
        }
    }

    @Test
    fun `machine and scope cancellation isolate equal request identities`() {
        val h = Harness()
        val first = packet(1, 2, 11)
        val second = packet(1, 3, 22)
        h.requests.inspect(1, first)
        h.requests.inspect(2, second)
        h.requests.cancel(1, 22) // A suspended parent's scope is still live.
        h.requests.cancel(2, 22)
        h.drain()
        assertEquals(listOf(Triple(1L, first.identity, 2)), h.costs)
        assertSame(first, h.requests.take(1, first.identity, 11))
        assertNull(h.requests.take(2, second.identity, 22))
        h.requests.inspect(1, first)
        h.requests.cancel(1)
        assertEquals(0L, h.requests.retainedBytes)
        h.drain()
        assertEquals(1, h.costs.size)
        h.requests.inspect(2, second)
        assertNull(h.requests.take(2, second.identity, 11)) // Wrong scope cannot reuse a prepared packet.
        assertEquals(0L, h.requests.retainedBytes)
    }

    private fun packet(
        id: Long,
        count: Int,
        scope: Long = 0,
    ): ProgramAddonRequest =
        ProgramAddonRequest(
            VmHostRequestIdentity(1, id),
            NativeFleetCommandRequests.INSPECTION.capability,
            0,
            listOf(
                VmValue.ByteArrayValue(
                    CommandBatchCodec.encode(
                        List(count) {
                            CommandBatchCodec.Command("fixture.Binding.write", listOf(VmValue.I32(it)))
                        },
                    ),
                ),
            ),
            scope,
        )

    private class Harness(
        entries: Int = 4,
        bytes: Long = 65536,
        perMachine: Int = entries,
    ) {
        var busy = false
        val costs = mutableListOf<Triple<Long, VmHostRequestIdentity, Int>>()
        private val fleet =
            Proxy.newProxyInstance(ExperimentalFleet::class.java.classLoader, arrayOf(ExperimentalFleet::class.java)) { _, method, args ->
                when (method.name) {
                    "resolveCost" -> {
                        if (busy) throw VmFleetBusyException("full")
                        costs += Triple(args!![0] as Long, args[1] as VmHostRequestIdentity, args[2] as Int)
                        null
                    }

                    "redstoneInput" -> {
                        null
                    }

                    else -> {
                        error("unexpected call: ${method.name}")
                    }
                }
            } as ExperimentalFleet
        val outbox = NativeFleetOutbox(fleet, entries, entries, {}, byteLimits = NativeFleetOutboxLimits(bytes, bytes))
        val requests = NativeFleetCommandRequests(outbox, entries, perMachine, NativeFleetOutboxLimits(bytes, bytes)) { 0 }

        fun drain() {
            repeat(4) {
                requests.pump(4, Long.MAX_VALUE)
                outbox.flush(4)
            }
        }
    }
}
