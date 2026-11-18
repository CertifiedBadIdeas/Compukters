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
    fun `autonomous accounting preserves execution costs and rejects malformed limits and reasons`() {
        val wire =
            ByteBuffer
                .allocate(47)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(19)
                .putLong(7)
                .putLong(12)
                .putLong(3)
                .put(0)
                .putLong(1234)
                .putInt(97)
                .putInt(15)
                .putInt(31)
                .put(7)
                .array()
        assertEquals(
            ExperimentalFleetEvent.Quiescent(
                7,
                12,
                3,
                false,
                FleetFrameProgress(1234, 97, 15, 31, FleetWaitReason.INSPECTION),
            ),
            ExperimentalFleetEventWire.decode(wire),
        )
        for (end in 0 until wire.size) assertFails { ExperimentalFleetEventWire.decode(wire.copyOf(end)) }
        assertFails { ExperimentalFleetEventWire.decode(wire + byteArrayOf(0)) }
        for (offset in listOf(37, 41, 45, 46)) {
            val malformed = wire.copyOf()
            malformed[offset] = 255.toByte()
            assertFails { ExperimentalFleetEventWire.decode(malformed) }
        }
    }

    @Test
    fun `buffer decoder honors position limit and tags for heap direct and read only views`() {
        val wires =
            listOf(
                ByteBuffer
                    .allocate(26)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(8)
                    .putLong(7)
                    .putLong(12)
                    .putLong(3)
                    .put(1)
                    .array(),
                ByteBuffer
                    .allocate(9)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(6)
                    .putLong(7)
                    .array(),
                ByteBuffer
                    .allocate(9)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(7)
                    .putLong(7)
                    .array(),
                ByteBuffer
                    .allocate(12)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(3)
                    .putLong(7)
                    .put("bad".encodeToByteArray())
                    .array(),
                byteArrayOf(5) + "bad".encodeToByteArray(),
                ByteBuffer
                    .allocate(26)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(0)
                    .putLong(7)
                    .putLong(9)
                    .put(13)
                    .putLong(3)
                    .array(),
            )
        for (wire in wires) {
            val expected = ExperimentalFleetEventWire.decode(wire)
            for (storage in listOf(ByteBuffer.allocate(wire.size + 8), ByteBuffer.allocateDirect(wire.size + 8))) {
                storage.position(3).put(wire).put(127)
                for (input in listOf(storage, storage.asReadOnlyBuffer())) {
                    input.clear().position(3).limit(3 + wire.size)
                    input.order(ByteOrder.BIG_ENDIAN)
                    assertEquals(expected, ExperimentalFleetEventWire.decode(input))
                    assertEquals(input.limit(), input.position())
                }
            }
        }
    }

    @Test
    fun `reusable buffer validates the current event length instead of its capacity`() {
        val wire =
            ByteBuffer
                .allocate(26)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(8)
                .putLong(7)
                .putLong(12)
                .putLong(3)
                .put(1)
                .array()
        val input = ByteBuffer.allocateDirect(1_048_577).put(wire)
        for (length in 0 until wire.size) {
            assertFails { ExperimentalFleetEventWire.decode(input.clear().limit(length)) }
        }
        assertFails { ExperimentalFleetEventWire.decode(input.clear().limit(wire.size + 1)) }
        assertFails { ExperimentalFleetEventWire.decode(input.clear()) }
        assertEquals(ExperimentalFleetEvent.Quiescent(7, 12, 3, true), ExperimentalFleetEventWire.decode(input.clear().limit(wire.size)))
    }

    @Test
    fun `detach state validates scopes output and timer ownership`() {
        fun wire(
            scopes: List<Long> = listOf(0, 7),
            redstone: Int = 9,
            timerScope: Long = 7,
        ): ByteArray =
            ByteBuffer
                .allocate(21 + scopes.size * 8 + 32)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(18)
                .putLong(3)
                .putInt(redstone)
                .putInt(scopes.size)
                .apply { scopes.forEach(::putLong) }
                .putInt(1)
                .putInt(1)
                .putLong(2)
                .putLong(timerScope)
                .putInt(8)
                .putLong(4)
                .array()
        val event = ExperimentalFleetEventWire.decode(wire()) as ExperimentalFleetEvent.Detached
        assertEquals(ExperimentalFleetDetachedState(listOf(0, 7), 9), event.state)
        assertEquals(7L, event.timers.single().scope)
        for (size in wire().indices) assertFails { ExperimentalFleetEventWire.decode(wire().copyOf(size)) }
        assertFails { ExperimentalFleetEventWire.decode(wire() + 0) }
        for (scopes in listOf(emptyList(), listOf(-1L), listOf(0L, 0L), listOf(1L, 0L), (0L..32).toList())) {
            assertFails { ExperimentalFleetEventWire.decode(wire(scopes)) }
        }
        assertFails { ExperimentalFleetEventWire.decode(wire(redstone = -1)) }
        assertFails { ExperimentalFleetEventWire.decode(wire(timerScope = 8)) }
    }

    @Test
    fun `quiescent report validates pending timer flag`() {
        fun wire(flag: Int) =
            ByteBuffer
                .allocate(26)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(8)
                .putLong(7)
                .putLong(12)
                .putLong(3)
                .put(flag.toByte())
                .array()
        assertEquals(ExperimentalFleetEvent.Quiescent(7, 12, 3, true), ExperimentalFleetEventWire.decode(wire(1)))
        assertEquals(ExperimentalFleetEvent.Quiescent(7, 12, 3, false), ExperimentalFleetEventWire.decode(wire(0)))
        assertFails { ExperimentalFleetEventWire.decode(wire(2)) }
        for (size in 0 until 26) assertFails { ExperimentalFleetEventWire.decode(wire(1).copyOf(size)) }
        assertFails { ExperimentalFleetEventWire.decode(wire(1) + 0) }
    }

    @Test
    fun `compilation event decodes checked source snapshot`() {
        val path = "/home/main.kt".encodeToByteArray()
        val source = "fun main() = 42".encodeToByteArray()
        val wire =
            ByteBuffer
                .allocate(1 + 16 + 2 + 8 + 4 + 4 + path.size + 4 + source.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(9)
                .putLong(7)
                .putLong(12)
                .putShort(1)
                .putLong(3)
                .putInt(1)
                .putInt(path.size)
                .put(path)
                .putInt(source.size)
                .put(source)
                .array()
        val event = ExperimentalFleetEventWire.decode(wire) as ExperimentalFleetEvent.Compilation
        assertEquals(7L, event.machine)
        assertEquals(12L, event.tick)
        assertEquals(3L, event.request.token)
        assertEquals(
            "/home/main.kt",
            event.request.sources
                .single()
                .path,
        )
        kotlin.test.assertContentEquals(
            source,
            event.request.sources
                .single()
                .utf8Bytes(),
        )
        for (size in 0 until wire.size) assertFails { ExperimentalFleetEventWire.decode(wire.copyOf(size)) }
        assertFails { ExperimentalFleetEventWire.decode(wire + 0) }
        val input = ByteBuffer.allocateDirect(wire.size).put(wire).flip()
        val owned = ExperimentalFleetEventWire.decode(input) as ExperimentalFleetEvent.Compilation
        input.clear().put(ByteArray(wire.size))
        kotlin.test.assertContentEquals(
            source,
            owned.request.sources
                .single()
                .utf8Bytes(),
        )
    }

    @Test
    fun `deployment events validate results and preserve failures`() {
        fun wire(
            kind: Int,
            status: Int,
            value: Long,
        ): ByteArray =
            ByteBuffer
                .allocate(30)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(17)
                .putLong(7)
                .putLong(8)
                .put(kind.toByte())
                .putInt(status)
                .putLong(value)
                .array()
        val prepared = wire(0, 0, 9)
        assertEquals(
            ExperimentalFleetEvent.Deployment(7, 8, ExperimentalFleetDeploymentResult.Prepared(9)),
            ExperimentalFleetEventWire.decode(prepared),
        )
        assertEquals(
            ExperimentalFleetEvent.Deployment(7, 8, ExperimentalFleetDeploymentResult.Revision(VmExecutableRevision.Absent)),
            ExperimentalFleetEventWire.decode(wire(1, 0, 0)),
        )
        assertEquals(
            ExperimentalFleetEvent.Deployment(7, 8, ExperimentalFleetDeploymentResult.Failed(ExperimentalFleetDeploymentFailure.BUSY)),
            ExperimentalFleetEventWire.decode(wire(4, 38, 0)),
        )
        for (size in 0 until prepared.size) assertFails { ExperimentalFleetEventWire.decode(prepared.copyOf(size)) }
        assertFails { ExperimentalFleetEventWire.decode(prepared + 0) }
        assertFails { ExperimentalFleetEventWire.decode(wire(0, 0, 0)) }
        assertFails { ExperimentalFleetEventWire.decode(wire(4, 38, 9)) }
        assertFails { ExperimentalFleetEventWire.decode(wire(4, 0, 0)) }
    }

    @Test
    fun `detached timer event validates lengths identities and remaining duration`() {
        fun wire(
            remaining: Long = 1,
            duplicate: Boolean = false,
        ): ByteArray {
            val count = if (duplicate) 2 else 1
            val buffer =
                ByteBuffer
                    .allocate(13 + count * 32)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(16)
                    .putLong(7)
                    .putInt(count)
            repeat(count) {
                buffer
                    .putInt(2)
                    .putLong(19)
                    .putLong(3)
                    .putInt(2)
                    .putLong(remaining)
            }
            return buffer.array()
        }
        val encoded = wire()
        assertEquals(
            ExperimentalFleetEvent.Detached(7, listOf(ExperimentalFleetTimer(VmHostRequestIdentity(2, 19), 3, 2, 1))),
            ExperimentalFleetEventWire.decode(encoded),
        )
        for (size in 0 until encoded.size) assertFails { ExperimentalFleetEventWire.decode(encoded.copyOf(size)) }
        assertFails { ExperimentalFleetEventWire.decode(encoded + 0) }
        assertFails { ExperimentalFleetEventWire.decode(wire(remaining = 3)) }
        assertFails { ExperimentalFleetEventWire.decode(wire(duplicate = true)) }
    }

    @Test
    fun `scope cancellation preserves machine scope and identities and rejects malformed messages`() {
        val wire =
            ByteBuffer
                .allocate(33)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(4)
                .putLong(7)
                .putLong(3)
                .putInt(1)
                .putInt(2)
                .putLong(19)
                .array()
        assertEquals(
            ExperimentalFleetEvent.ScopeStopped(7, 3, listOf(VmHostRequestIdentity(2, 19))),
            ExperimentalFleetEventWire.decode(wire),
        )
        for (size in 0 until wire.size) assertFails { ExperimentalFleetEventWire.decode(wire.copyOf(size)) }
        assertFails { ExperimentalFleetEventWire.decode(wire + 0) }
    }

    @Test
    fun `stop event carries final generation and rejects old or malformed encodings`() {
        fun wire(
            generation: Long = 0x1_0000_0002L,
            duplicate: Boolean = false,
        ): ByteArray {
            val count = if (duplicate) 2 else 1
            val buffer =
                ByteBuffer
                    .allocate(21 + 12 * count)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put(2)
                    .putLong(7)
                    .putLong(generation)
                    .putInt(count)
            repeat(count) { buffer.putInt(2).putLong(19) }
            return buffer.array()
        }
        val encoded = wire()
        assertEquals(
            ExperimentalFleetEvent.Stopped(7, listOf(VmHostRequestIdentity(2, 19)), 0x1_0000_0002L),
            ExperimentalFleetEventWire.decode(encoded),
        )
        for (size in 0 until encoded.size) assertFails { ExperimentalFleetEventWire.decode(encoded.copyOf(size)) }
        assertFails { ExperimentalFleetEventWire.decode(encoded + 0) }
        assertFails { ExperimentalFleetEventWire.decode(wire(generation = -1)) }
        assertFails { ExperimentalFleetEventWire.decode(wire(duplicate = true)) }
        val old =
            ByteBuffer
                .allocate(13)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(2)
                .putLong(7)
                .putInt(0)
                .array()
        assertFails { ExperimentalFleetEventWire.decode(old) }
        val wrongCount = encoded.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(17, -1) }
        assertFails { ExperimentalFleetEventWire.decode(wrongCount) }
    }

    @Test
    fun `machine errors retain their source`() {
        val wire =
            ByteBuffer
                .allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(3)
                .putLong(7)
                .put("bad".encodeToByteArray())
                .array()
        assertEquals(ExperimentalFleetEvent.Error(7, "bad"), ExperimentalFleetEventWire.decode(wire))
        assertFails { ExperimentalFleetEventWire.decode(wire.copyOf(8)) }
    }

    @Test
    fun `process event uses the existing outcome decoder`() {
        val wire =
            ByteBuffer
                .allocate(26)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(0)
                .putLong(7)
                .putLong(9)
                .put(13)
                .putLong(3)
                .array()
        assertEquals(ExperimentalFleetEvent.Outcome(7, 9, VmOutcome.ProcessEntered(3)), ExperimentalFleetEventWire.decode(wire))
    }
}
