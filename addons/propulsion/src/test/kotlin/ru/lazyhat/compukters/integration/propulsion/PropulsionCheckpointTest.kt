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

package ru.lazyhat.compukters.integration.propulsion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PropulsionCheckpointTest {
    @Test
    fun `command state round trips including optional overrides`() {
        val snapshot =
            PropulsionCheckpoint(
                listOf(ThrusterCommand(1, true, 0.7f, 45)),
                listOf(
                    ThrusterCommand(2, true, 0.8f, vectorX = -0.1f, vectorY = 0.2f, thrustOutput = 32000f),
                    ThrusterCommand(3, false, 0f),
                ),
            )
        assertEquals(snapshot, PropulsionCheckpoint.decode(snapshot.encode()))
        assertEquals(
            PropulsionCheckpoint(emptyList(), emptyList()),
            PropulsionCheckpoint.decode(PropulsionCheckpoint(emptyList(), emptyList()).encode()),
        )
    }

    @Test
    fun `malformed descriptors reject before restoration`() {
        val valid = PropulsionCheckpoint(listOf(ThrusterCommand(1, true, 0.5f, 20)), emptyList()).encode()
        assertFailsWith<IllegalArgumentException> { PropulsionCheckpoint.decode(valid + byteArrayOf(0)) }
        valid.indices.forEach { size -> assertFailsWith<IllegalArgumentException> { PropulsionCheckpoint.decode(valid.copyOf(size)) } }
        assertFailsWith<IllegalArgumentException> { PropulsionCheckpoint.decode(valid.copyOf().also { it[0] = 2 }) }
        assertFailsWith<IllegalArgumentException> { PropulsionCheckpoint.decode(valid.copyOf().also { it[12] = 2 }) }
        listOf(
            ThrusterCommand(0, true, 0f, 20),
            ThrusterCommand(1, true, Float.NaN, 20),
            ThrusterCommand(1, true, 2f, 20),
            ThrusterCommand(1, true, 0f, 101),
        ).forEach { command -> assertFailsWith<IllegalArgumentException> { PropulsionCheckpoint(listOf(command), emptyList()).encode() } }
        listOf(
            ThrusterCommand(1, true, 0f, vectorX = 0f),
            ThrusterCommand(1, true, 0f, vectorX = 2f, vectorY = 0f),
            ThrusterCommand(1, true, 0f, thrustOutput = Float.POSITIVE_INFINITY),
            ThrusterCommand(1, true, 0f, thrustOutput = -1f),
        ).forEach { command -> assertFailsWith<IllegalArgumentException> { PropulsionCheckpoint(emptyList(), listOf(command)).encode() } }
        assertFailsWith<IllegalArgumentException> {
            PropulsionCheckpoint(listOf(ThrusterCommand(1, true, 0f, 20)), listOf(ThrusterCommand(1, true, 0f))).encode()
        }
        assertFailsWith<IllegalArgumentException> {
            PropulsionCheckpoint(
                emptyList(),
                List(1025) { ThrusterCommand(it + 1, false, 0f) },
            ).encode()
        }
    }
}
