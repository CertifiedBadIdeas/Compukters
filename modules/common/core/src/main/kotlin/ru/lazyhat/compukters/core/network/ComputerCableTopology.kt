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

enum class CableNode { EMPTY, CABLE, COMPUTER, UNLOADED }

enum class CableRejection { BRANCH, PORT_OCCUPIED, LOOP, TOO_MANY_COMPUTERS, LIMIT_REACHED, UNLOADED }

sealed interface ComputerCableResult<out N> {
    data class Line<N>(
        val nodes: Set<N>,
        val computers: List<N>,
    ) : ComputerCableResult<N>

    data class Rejected(
        val reason: CableRejection,
    ) : ComputerCableResult<Nothing>
}

/** Physical adjacency only; computers are terminal endpoints, never forwarding nodes. */
class ComputerCableTopology<N>(
    private val kind: (N) -> CableNode,
    private val neighbors: (N) -> Iterable<N>,
    private val maximumCables: Int = 4096,
) {
    init {
        require(maximumCables > 0)
    }

    fun inspect(start: N): ComputerCableResult<N> {
        val seen = linkedSetOf<N>()
        val parents = hashMapOf<N, N>()
        val pending = ArrayDeque<N>()
        val computers = mutableListOf<N>()
        var cables = 0
        var visits = 0
        var unloaded = false
        seen += start
        pending += start
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val type = kind(node)
            if (type == CableNode.UNLOADED) return ComputerCableResult.Rejected(CableRejection.UNLOADED)
            if (type == CableNode.EMPTY) return ComputerCableResult.Line(emptySet(), emptyList())
            if (type == CableNode.COMPUTER) {
                computers += node
                if (computers.size > 2) return ComputerCableResult.Rejected(CableRejection.TOO_MANY_COMPUTERS)
            } else if (++cables > maximumCables) {
                return ComputerCableResult.Rejected(CableRejection.LIMIT_REACHED)
            }
            var degree = 0
            for (neighbor in neighbors(node)) {
                if (++visits > (maximumCables + 2).toLong() * 6) return ComputerCableResult.Rejected(CableRejection.LIMIT_REACHED)
                val other = kind(neighbor)
                if (other == CableNode.UNLOADED) {
                    unloaded = true
                    continue
                }
                if (other == CableNode.EMPTY || (type == CableNode.COMPUTER && other == CableNode.COMPUTER)) continue
                degree++
                if (degree > if (type == CableNode.COMPUTER) 1 else 2) {
                    return ComputerCableResult.Rejected(
                        if (type ==
                            CableNode.COMPUTER
                        ) {
                            CableRejection.PORT_OCCUPIED
                        } else {
                            CableRejection.BRANCH
                        },
                    )
                }
                if (neighbor == parents[node]) continue
                if (!seen.add(neighbor)) return ComputerCableResult.Rejected(CableRejection.LOOP)
                parents[neighbor] = node
                pending += neighbor
            }
        }
        return if (unloaded) {
            ComputerCableResult.Rejected(CableRejection.UNLOADED)
        } else {
            ComputerCableResult.Line(seen.toSet(), computers.toList())
        }
    }
}
