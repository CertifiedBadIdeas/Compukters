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

internal data class PerComputerCapacityGrant(
    val endpoint: VmActorEndpoint,
    val retiredInstructionLimit: Long,
)

internal data class PerComputerCapacityAllocation(
    val grants: List<PerComputerCapacityGrant>,
    val requestedInstructions: Long,
    val reservedInstructions: Long,
) {
    val missedInstructions: Long get() = requestedInstructions - reservedInstructions
}

/** Divides one tick's instruction capacity between submitted runnable computers without carrying debt forward. */
internal object PerComputerCapacityAllocator {
    fun allocate(
        runnable: Collection<VmActorEndpoint>,
        perComputerEntitlement: Int,
        safeCapacity: Long,
        worldTick: Long,
    ): PerComputerCapacityAllocation {
        require(perComputerEntitlement > 0) { "per-computer instruction entitlement must be positive" }
        require(safeCapacity >= 0) { "safe instruction capacity must not be negative" }
        require(worldTick >= 0) { "world tick must not be negative" }
        if (runnable.isEmpty()) return PerComputerCapacityAllocation(emptyList(), 0, 0)

        val ordered =
            runnable.sortedWith { first, second ->
                compareIds(first.computerId, second.computerId)
            }
        require(ordered.zipWithNext().none { (first, second) -> first.computerId == second.computerId }) {
            "one computer must not appear twice in a capacity frame"
        }

        val requested = ordered.size.toLong() * perComputerEntitlement
        val reserved = minOf(requested, safeCapacity)
        val commonGrant = reserved / ordered.size
        val extraGrants = (reserved % ordered.size).toInt()
        val firstExtra = (worldTick % ordered.size).toInt()
        val grants =
            ordered.mapIndexed { index, endpoint ->
                val rotatedIndex = Math.floorMod(index - firstExtra, ordered.size)
                PerComputerCapacityGrant(endpoint, commonGrant + if (rotatedIndex < extraGrants) 1 else 0)
            }
        return PerComputerCapacityAllocation(grants, requested, reserved)
    }

    private fun compareIds(
        first: ComputerId,
        second: ComputerId,
    ): Int {
        val highBits = java.lang.Long.compareUnsigned(first.highBits, second.highBits)
        return if (highBits != 0) highBits else java.lang.Long.compareUnsigned(first.lowBits, second.lowBits)
    }
}
