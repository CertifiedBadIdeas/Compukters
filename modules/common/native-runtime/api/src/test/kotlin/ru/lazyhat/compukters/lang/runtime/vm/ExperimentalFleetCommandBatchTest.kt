/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExperimentalFleetCommandBatchTest {
    @Test
    fun `fixed records retain original deadlines until submission and reset outcomes on reuse`() {
        val batch = ExperimentalFleetCommandBatch()
        val storage = batch.transportBuffer
        batch.grant(1, 2, 4096, 150)
        batch.grant(2, 2, 1, Long.MAX_VALUE)
        batch.grant(3, 2, 1, 50)
        batch.resolveCost(4, VmHostRequestIdentity(5, 6), 7)
        batch.redstoneInput(5, 123)
        batch.confirmRedstoneOutput(6, 456)
        assertEquals(150L, storage.getLong(32))
        batch.prepare(100)
        assertEquals(1, storage.getInt(0))
        assertEquals(6, storage.getInt(4))
        assertEquals(50L, storage.getLong(32))
        assertEquals(-1L, storage.getLong(72))
        assertEquals(0L, storage.getLong(112))
        assertEquals(7L, storage.getLong(152))
        assertEquals(ExperimentalFleetCommandBatch.NOT_ATTEMPTED, batch.result(0))
        storage.put(ExperimentalFleetCommandBatch.INPUT_BYTES, 0)
        assertEquals(0, batch.result(0))
        assertFailsWith<IllegalStateException> { batch.prepare(100) }
        batch.clear()
        batch.redstoneInput(1, 0)
        assertTrue(storage === batch.transportBuffer)
        assertEquals(ExperimentalFleetCommandBatch.NOT_ATTEMPTED, batch.result(0))
        assertEquals(48, batch.inputLength)
    }

    @Test
    fun `capacity and invalid scalar values are rejected before adding a record`() {
        val batch = ExperimentalFleetCommandBatch()
        assertFailsWith<IllegalStateException> { batch.prepare() }
        assertFailsWith<IllegalArgumentException> { batch.grant(-1, 1, 1, Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { batch.redstoneInput(1, 1 shl 30) }
        assertEquals(0, batch.size)
        repeat(ExperimentalFleetCommandBatch.MAXIMUM_COMMANDS) { batch.grant(it.toLong(), 1, 1, Long.MAX_VALUE) }
        assertFailsWith<IllegalStateException> { batch.grant(17, 1, 1, Long.MAX_VALUE) }
        assertEquals(ExperimentalFleetCommandBatch.INPUT_BYTES, batch.inputLength)
        assertTrue(batch.transportBuffer.isDirect)
    }
}
