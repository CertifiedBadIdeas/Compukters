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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PeripheralCableTopologyTest {
    @Test
    fun `traverses branches and loops once in stable breadth-first order`() {
        val topology =
            topology(
                edges =
                    mapOf(
                        "a" to listOf("b", "c"),
                        "b" to listOf("a", "d"),
                        "c" to listOf("a", "d"),
                        "d" to listOf("b", "c"),
                    ),
            )

        val complete = assertIs<PeripheralCableTraversal.Complete<String, String>>(topology.traverse("a"))

        assertEquals(listOf("a", "b", "c", "d"), complete.cables.toList())
    }

    @Test
    fun `deduplicates contacts exposed by multiple cable nodes`() {
        val topology =
            topology(
                edges = mapOf("a" to listOf("b"), "b" to listOf("a")),
                contacts = mapOf("a" to listOf("motor"), "b" to listOf("motor", "gauge")),
            )

        val complete = assertIs<PeripheralCableTraversal.Complete<String, String>>(topology.traverse("a"))

        assertEquals(listOf("motor", "gauge"), complete.contacts.toList())
    }

    @Test
    fun `reports cable limit without exposing a partial component`() {
        val topology =
            topology(
                edges = mapOf("a" to listOf("b"), "b" to listOf("a", "c"), "c" to listOf("b")),
                limits = PeripheralCableLimits(maximumCables = 2, maximumNeighborVisits = 16, maximumContacts = 16),
            )

        assertEquals(
            PeripheralCableTraversal.LimitExceeded(PeripheralCableLimit.CABLES, 2),
            topology.traverse("a"),
        )
    }

    @Test
    fun `bounds neighbor work even when every neighbor was already visited`() {
        val topology =
            topology(
                edges = mapOf("a" to listOf("b", "c"), "b" to listOf("a"), "c" to listOf("a")),
                limits = PeripheralCableLimits(maximumCables = 8, maximumNeighborVisits = 2, maximumContacts = 8),
            )

        assertEquals(
            PeripheralCableTraversal.LimitExceeded(PeripheralCableLimit.NEIGHBOR_VISITS, 2),
            topology.traverse("a"),
        )
    }

    @Test
    fun `reports contact limit after deduplication`() {
        val topology =
            topology(
                edges = emptyMap(),
                contacts = mapOf("a" to listOf("one", "one", "two")),
                limits = PeripheralCableLimits(maximumCables = 1, maximumNeighborVisits = 0, maximumContacts = 1),
            )

        assertEquals(
            PeripheralCableTraversal.LimitExceeded(PeripheralCableLimit.CONTACTS, 1),
            topology.traverse("a"),
        )
    }

    @Test
    fun `a removed edge splits the component`() {
        val topology =
            topology(
                edges = mapOf("a" to listOf("b"), "b" to listOf("a"), "c" to emptyList()),
                contacts = mapOf("a" to listOf("left"), "c" to listOf("right")),
            )

        val left = assertIs<PeripheralCableTraversal.Complete<String, String>>(topology.traverse("a"))
        val right = assertIs<PeripheralCableTraversal.Complete<String, String>>(topology.traverse("c"))

        assertEquals(setOf("a", "b"), left.cables)
        assertEquals(setOf("left"), left.contacts)
        assertEquals(setOf("c"), right.cables)
        assertEquals(setOf("right"), right.contacts)
    }

    @Test
    fun `multiple roots join cable components through the attached computer`() {
        val topology =
            topology(
                edges = mapOf("left" to emptyList(), "right" to emptyList()),
                contacts = mapOf("left" to listOf("motor"), "right" to listOf("gauge")),
            )

        val complete =
            assertIs<PeripheralCableTraversal.Complete<String, String>>(
                topology.traverse(listOf("left", "right", "left")),
            )

        assertEquals(listOf("left", "right"), complete.cables.toList())
        assertEquals(listOf("motor", "gauge"), complete.contacts.toList())
    }

    private fun topology(
        edges: Map<String, List<String>>,
        contacts: Map<String, List<String>> = emptyMap(),
        limits: PeripheralCableLimits = PeripheralCableLimits(16, 64, 16),
    ): PeripheralCableTopology<String, String> =
        PeripheralCableTopology(
            limits = limits,
            neighbors = { edges[it].orEmpty() },
            contacts = { contacts[it].orEmpty() },
        )
}
