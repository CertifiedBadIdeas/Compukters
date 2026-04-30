package ru.lazyhat.compukterkraft.core.device.vm.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ScrollbackRingTest {
    @Test
    fun appendsUnderCapacityPreservesOrder() {
        val ring = ScrollbackRing(16)
        ring.append(byteArrayOf(1, 2, 3))
        ring.append(byteArrayOf(4, 5))
        assertEquals(5, ring.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), ring.snapshotBytes())
    }

    @Test
    fun overwritesOldestWhenOverflowing() {
        val ring = ScrollbackRing(4)
        ring.append(byteArrayOf(1, 2, 3))
        ring.append(byteArrayOf(4, 5, 6))
        assertEquals(4, ring.size)
        assertContentEquals(byteArrayOf(3, 4, 5, 6), ring.snapshotBytes())
    }

    @Test
    fun largeChunkKeepsOnlyTail() {
        val ring = ScrollbackRing(3)
        ring.append(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertContentEquals(byteArrayOf(5, 6, 7), ring.snapshotBytes())
    }

    @Test
    fun emptySnapshotWhenNothingWritten() {
        val ring = ScrollbackRing(8)
        assertEquals(0, ring.size)
        assertContentEquals(ByteArray(0), ring.snapshotBytes())
    }

    @Test
    fun wrapAroundSnapshotInTemporalOrder() {
        val ring = ScrollbackRing(5)
        ring.append(byteArrayOf(1, 2, 3, 4, 5))
        ring.append(byteArrayOf(6, 7))
        // writePos wrapped from 0 to 2; content order is 3,4,5,6,7
        assertContentEquals(byteArrayOf(3, 4, 5, 6, 7), ring.snapshotBytes())
    }
}
