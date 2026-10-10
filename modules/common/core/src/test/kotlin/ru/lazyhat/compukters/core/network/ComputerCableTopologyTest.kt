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

package ru.lazyhat.compukters.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ComputerCableTopologyTest {
    private fun graph(
        types: Map<Int, CableNode>,
        vararg edges: Pair<Int, Int>,
        limit: Int = 4096,
    ) = ComputerCableTopology<Int>(
        { types[it] ?: CableNode.EMPTY },
        { n ->
            edges.mapNotNull { (a, b) ->
                if (n == a) {
                    b
                } else if (n == b) {
                    a
                } else {
                    null
                }
            }
        },
        limit,
    )

    private val cable = CableNode.CABLE
    private val pc = CableNode.COMPUTER

    @Test fun `complete line has exactly two endpoint computers`() {
        val result = graph(mapOf(0 to pc, 1 to cable, 2 to cable, 3 to pc), 0 to 1, 1 to 2, 2 to 3).inspect(0)
        assertEquals(listOf(0, 3), assertIs<ComputerCableResult.Line<Int>>(result).computers)
    }

    @Test fun `incomplete line is valid and adjacent computers alone do not connect`() {
        assertEquals(listOf(0), assertIs<ComputerCableResult.Line<Int>>(graph(mapOf(0 to pc, 1 to pc), 0 to 1).inspect(0)).computers)
        assertEquals(
            emptyList(),
            assertIs<ComputerCableResult.Line<Int>>(graph(mapOf(0 to cable, 1 to cable), 0 to 1).inspect(0)).computers,
        )
    }

    @Test fun `branch cannot silently select two of three computers`() {
        assertEquals(
            ComputerCableResult.Rejected(CableRejection.BRANCH),
            graph(mapOf(0 to cable, 1 to pc, 2 to pc, 3 to pc), 0 to 1, 0 to 2, 0 to 3).inspect(0),
        )
    }

    @Test fun `computer has only one port even when second cable is an incomplete stub`() {
        assertEquals(
            ComputerCableResult.Rejected(CableRejection.PORT_OCCUPIED),
            graph(mapOf(0 to pc, 1 to cable, 2 to cable), 0 to 1, 0 to 2).inspect(0),
        )
    }

    @Test fun `loop is rejected`() {
        assertEquals(
            ComputerCableResult.Rejected(CableRejection.LOOP),
            graph(mapOf(0 to cable, 1 to cable, 2 to cable, 3 to cable), 0 to 1, 1 to 2, 2 to 3, 3 to 0).inspect(0),
        )
    }

    @Test fun `unloaded boundary and oversized lines never return partial peers`() {
        assertEquals(
            ComputerCableResult.Rejected(CableRejection.UNLOADED),
            graph(mapOf(0 to pc, 1 to cable, 2 to CableNode.UNLOADED), 0 to 1, 1 to 2).inspect(0),
        )
        assertEquals(
            ComputerCableResult.Rejected(CableRejection.LIMIT_REACHED),
            graph(mapOf(0 to pc, 1 to cable, 2 to cable, 3 to pc), 0 to 1, 1 to 2, 2 to 3, limit = 1).inspect(0),
        )
    }
}
