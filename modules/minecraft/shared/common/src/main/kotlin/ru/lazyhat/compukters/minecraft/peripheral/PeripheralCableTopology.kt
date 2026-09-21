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

package ru.lazyhat.compukters.minecraft.peripheral

internal data class PeripheralCableLimits(
    val maximumCables: Int,
    val maximumNeighborVisits: Int,
    val maximumContacts: Int,
) {
    init {
        require(maximumCables > 0) { "maximumCables must be positive" }
        require(maximumNeighborVisits >= 0) { "maximumNeighborVisits must not be negative" }
        require(maximumContacts >= 0) { "maximumContacts must not be negative" }
    }
}

internal enum class PeripheralCableLimit {
    CABLES,
    NEIGHBOR_VISITS,
    CONTACTS,
}

internal sealed interface PeripheralCableTraversal<out N, out C> {
    data class Complete<N, C>(
        val cables: Set<N>,
        val contacts: Set<C>,
    ) : PeripheralCableTraversal<N, C>

    data class LimitExceeded(
        val limit: PeripheralCableLimit,
        val maximum: Int,
    ) : PeripheralCableTraversal<Nothing, Nothing>
}

internal class PeripheralCableTopology<N, C>(
    private val limits: PeripheralCableLimits,
    private val neighbors: (N) -> Iterable<N>,
    private val contacts: (N) -> Iterable<C>,
) {
    fun traverse(start: N): PeripheralCableTraversal<N, C> = traverse(listOf(start))

    fun traverse(starts: Iterable<N>): PeripheralCableTraversal<N, C> {
        val visited = linkedSetOf<N>()
        val pending = ArrayDeque<N>()
        for (start in starts) {
            if (visited.add(start)) {
                if (visited.size > limits.maximumCables) {
                    return PeripheralCableTraversal.LimitExceeded(
                        PeripheralCableLimit.CABLES,
                        limits.maximumCables,
                    )
                }
                pending.addLast(start)
            }
        }
        val discoveredContacts = linkedSetOf<C>()
        var neighborVisits = 0

        while (pending.isNotEmpty()) {
            val cable = pending.removeFirst()
            for (contact in contacts(cable)) {
                if (discoveredContacts.add(contact) && discoveredContacts.size > limits.maximumContacts) {
                    return PeripheralCableTraversal.LimitExceeded(
                        PeripheralCableLimit.CONTACTS,
                        limits.maximumContacts,
                    )
                }
            }
            for (neighbor in neighbors(cable)) {
                neighborVisits++
                if (neighborVisits > limits.maximumNeighborVisits) {
                    return PeripheralCableTraversal.LimitExceeded(
                        PeripheralCableLimit.NEIGHBOR_VISITS,
                        limits.maximumNeighborVisits,
                    )
                }
                if (visited.add(neighbor)) {
                    if (visited.size > limits.maximumCables) {
                        return PeripheralCableTraversal.LimitExceeded(
                            PeripheralCableLimit.CABLES,
                            limits.maximumCables,
                        )
                    }
                    pending.addLast(neighbor)
                }
            }
        }

        return PeripheralCableTraversal.Complete(visited, discoveredContacts)
    }
}
