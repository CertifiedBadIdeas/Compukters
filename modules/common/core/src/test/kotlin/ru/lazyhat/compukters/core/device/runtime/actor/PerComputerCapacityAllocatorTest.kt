/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.device.runtime.actor

import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PerComputerCapacityAllocatorTest {
    @Test
    fun `overload rotates equal instruction grants without accumulating missed work`() {
        val computers = (1L..3L).map(::endpoint)
        val allocations =
            (0L..2L).map { tick ->
                PerComputerCapacityAllocator.allocate(computers.reversed(), 10, 7, tick)
            }

        assertEquals(listOf(listOf(3L, 2L, 2L), listOf(2L, 3L, 2L), listOf(2L, 2L, 3L)), allocations.map(::grants))
        allocations.forEach { allocation ->
            assertEquals(30L, allocation.requestedInstructions)
            assertEquals(7L, allocation.reservedInstructions)
            assertEquals(23L, allocation.missedInstructions)
        }
    }

    @Test
    fun `capacity smaller than fleet still gives every runnable computer progress`() {
        val computers = (1L..4L).map(::endpoint)
        val delivered = LongArray(computers.size)
        repeat(4) { tick ->
            grants(PerComputerCapacityAllocator.allocate(computers, 10, 2, tick.toLong()))
                .forEachIndexed { index, amount -> delivered[index] += amount }
        }
        assertEquals(listOf(2L, 2L, 2L, 2L), delivered.toList())
    }

    @Test
    fun `unsubmitted computers consume no capacity and membership changes do not retain debt`() {
        val first = endpoint(1)
        val second = endpoint(2)
        val third = endpoint(3)
        val before = PerComputerCapacityAllocator.allocate(listOf(first, second, third), 10, 6, 0)
        val after = PerComputerCapacityAllocator.allocate(listOf(first, third), 10, 6, 1)

        assertEquals(listOf(2L, 2L, 2L), grants(before))
        assertEquals(listOf(3L, 3L), grants(after))
        assertEquals(14L, after.missedInstructions)
        assertEquals(0L, PerComputerCapacityAllocator.allocate(emptyList(), 10, 6, 1).reservedInstructions)
    }

    @Test
    fun `large limits remain exact and one computer identity cannot appear twice`() {
        val first = endpoint(1)
        val second = endpoint(2)
        val allocation =
            PerComputerCapacityAllocator.allocate(listOf(second, first), Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)

        assertEquals(listOf(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong()), grants(allocation))
        assertEquals(2L * Int.MAX_VALUE, allocation.requestedInstructions)
        assertEquals(0L, allocation.missedInstructions)
        assertFailsWith<IllegalArgumentException> {
            PerComputerCapacityAllocator.allocate(listOf(first, first.copy(epoch = 2)), 10, 10, 0)
        }
    }

    private fun grants(allocation: PerComputerCapacityAllocation): List<Long> =
        allocation.grants.map(PerComputerCapacityGrant::retiredInstructionLimit)

    private fun endpoint(number: Long) = VmActorEndpoint(ComputerId.fromLongs(0, number), 1)
}
