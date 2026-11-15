/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ExperimentalFleetEventTest {
    @Test
    fun `scope cancellation preserves machine scope and identities and rejects malformed messages`() {
        val wire = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
            .put(4).putLong(7).putLong(3).putInt(1).putInt(2).putLong(19).array()
        assertEquals(ExperimentalFleetEvent.ScopeStopped(7, 3, listOf(VmHostRequestIdentity(2, 19))), ExperimentalFleetEventWire.decode(wire))
        for (size in 0 until wire.size) assertFails { ExperimentalFleetEventWire.decode(wire.copyOf(size)) }
        assertFails { ExperimentalFleetEventWire.decode(wire + 0) }
    }

    @Test
    fun `machine errors retain their source`() {
        val wire = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(3).putLong(7).put("bad".encodeToByteArray()).array()
        assertEquals(ExperimentalFleetEvent.Error(7, "bad"), ExperimentalFleetEventWire.decode(wire))
        assertFails { ExperimentalFleetEventWire.decode(wire.copyOf(8)) }
    }

    @Test
    fun `process event uses the existing outcome decoder`() {
        val wire = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
            .put(0).putLong(7).putLong(9).put(13).putLong(3).array()
        assertEquals(ExperimentalFleetEvent.Outcome(7, 9, VmOutcome.ProcessEntered(3)), ExperimentalFleetEventWire.decode(wire))
    }
}
