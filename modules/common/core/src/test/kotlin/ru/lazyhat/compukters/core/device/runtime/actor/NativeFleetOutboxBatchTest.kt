/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleet
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetCommandBatch
import ru.lazyhat.compukters.lang.runtime.vm.ExperimentalFleetCommandTransport
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFleetOutboxBatchTest {
    private fun input(value: Int) = NativeFleetOutbox.Command.RedstoneInput(value)

    private fun fleet(
        sent: MutableList<Int>,
        submit: (ExperimentalFleetCommandBatch) -> Unit,
    ): ExperimentalFleet {
        val scalar =
            Proxy.newProxyInstance(ExperimentalFleet::class.java.classLoader, arrayOf(ExperimentalFleet::class.java)) { _, method, args ->
                check(method.name == "redstoneInput")
                sent += args!![1] as Int
                null
            } as ExperimentalFleet
        return object : ExperimentalFleet by scalar, ExperimentalFleetCommandTransport {
            override fun submitCommands(batch: ExperimentalFleetCommandBatch) = submit(batch)
        }
    }

    private fun accept(
        batch: ExperimentalFleetCommandBatch,
        index: Int,
        sent: MutableList<Int>,
    ) {
        sent += batch.transportBuffer.getInt(8 + index * 40 + 12)
        batch.transportBuffer.put(ExperimentalFleetCommandBatch.INPUT_BYTES + index, 0)
    }

    @Test
    fun `batch accepts one ordered head per machine and respects remaining allowance`() {
        val sent = mutableListOf<Int>()
        val sizes = mutableListOf<Int>()
        val bridge =
            fleet(sent) { batch ->
                sizes += batch.size
                batch.prepare()
                repeat(batch.size) { accept(batch, it, sent) }
            }
        val outbox = NativeFleetOutbox(bridge, 64, 64, {})
        assertTrue(outbox.offer(1, listOf(input(1), input(2))))
        assertTrue(outbox.offer(2, input(3)))
        assertEquals(2, outbox.sendNextGroup(16, Long.MAX_VALUE))
        assertEquals(listOf(1, 3), sent)
        assertEquals(64L, outbox.retainedBytes)
        assertEquals(1, outbox.sendNextGroup(16, Long.MAX_VALUE))
        assertEquals(listOf(1, 3, 2), sent)
        for (machine in 1L..20L) assertTrue(outbox.offer(machine, input(machine.toInt())))
        assertEquals(3, outbox.sendNextGroup(3, Long.MAX_VALUE))
        assertEquals(16, outbox.sendNextGroup(32, Long.MAX_VALUE))
        assertEquals(listOf(2, 3, 16), sizes)
        assertEquals(64L, outbox.retainedBytes)
    }

    @Test
    fun `busy and permanent failure preserve their heads while accepted peers release capacity`() {
        val sent = mutableListOf<Int>()
        val bridge =
            fleet(sent) { batch ->
                batch.prepare()
                batch.transportBuffer.put(ExperimentalFleetCommandBatch.INPUT_BYTES, 7)
                batch.transportBuffer.put(ExperimentalFleetCommandBatch.INPUT_BYTES + 1, 2)
                accept(batch, 2, sent)
            }
        val outbox = NativeFleetOutbox(bridge, 3, 3, {})
        for (machine in 1L..3L) assertTrue(outbox.offer(machine, input(machine.toInt())))
        val failure = assertFailsWith<NativeFleetCommandException> { outbox.sendNextGroup(3, Long.MAX_VALUE) }
        assertEquals(2L, failure.machine)
        assertEquals(listOf(3), sent)
        assertEquals(128L, outbox.retainedBytes)
        assertEquals(listOf(input(2)), outbox.remove(2))
        assertEquals(1, outbox.sendNextGroup(1, Long.MAX_VALUE))
        assertEquals(listOf(3, 1), sent)
        assertFalse(outbox.hasPendingCommands)
        assertEquals(0L, outbox.retainedBytes)
    }

    @Test
    fun `transport failure reconciles accepted prefix and keeps unattempted commands for close`() {
        val sent = mutableListOf<Int>()
        val bridge =
            fleet(sent) { batch ->
                batch.prepare()
                accept(batch, 0, sent)
                throw VmBridgeException("transport")
            }
        val outbox = NativeFleetOutbox(bridge, 3, 3, {})
        assertTrue(outbox.offer(1, input(1)))
        assertTrue(outbox.offer(2, input(2)))
        assertFailsWith<VmBridgeException> { outbox.sendNextGroup(3, Long.MAX_VALUE) }
        assertEquals(64L, outbox.retainedBytes)
        val handoff = outbox.handoffForClose(2)
        assertTrue(handoff.sendNext(bridge, 2))
        assertFalse(handoff.sendNext(bridge, 2))
        assertEquals(listOf(1, 2), sent)
        assertEquals(0L, outbox.retainedBytes)
    }

    @Test
    fun `failed validation restores selected heads and transport failure before prepare retains ownership`() {
        val sent = mutableListOf<Int>()
        val bridge = fleet(sent) { throw VmBridgeException("closed") }
        val outbox = NativeFleetOutbox(bridge, 4, 4, {})
        assertTrue(outbox.offer(1, input(1)))
        assertTrue(outbox.offer(2, input(1 shl 30)))
        val failure = assertFailsWith<NativeFleetCommandException> { outbox.sendNextGroup(4, Long.MAX_VALUE) }
        assertEquals(2L, failure.machine)
        assertTrue(failure.cause is IllegalArgumentException)
        assertEquals(listOf(input(1)), outbox.remove(1))
        outbox.remove(2)
        assertTrue(outbox.offer(1, input(1)))
        assertTrue(outbox.offer(2, input(2)))
        assertFailsWith<VmBridgeException> { outbox.sendNextGroup(4, Long.MAX_VALUE) }
        assertEquals(128L, outbox.retainedBytes)
        assertEquals(listOf(input(1)), outbox.remove(1))
        assertEquals(listOf(input(2)), outbox.remove(2))
        assertEquals(0L, outbox.retainedBytes)
    }
}
