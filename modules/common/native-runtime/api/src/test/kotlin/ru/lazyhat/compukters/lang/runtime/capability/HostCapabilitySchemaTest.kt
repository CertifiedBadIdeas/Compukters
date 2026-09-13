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

package ru.lazyhat.compukters.lang.runtime.capability

import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostCapabilitySchemaTest {
    @Test
    fun `schema wire is canonical and owns operation inputs`() {
        val arguments = mutableListOf(HostValueType.I32, HostValueType.F32)
        val operations = mutableListOf(HostOperationSchema(arguments, HostValueType.UNIT, asynchronous = true))
        val schema = HostCapabilitySchema(CapabilityIdentity("create", "kinetics", 1, 0), operations)

        arguments.clear()
        operations.clear()

        assertEquals(listOf(HostValueType.I32, HostValueType.F32), schema.operations.single().arguments)
        assertContentEquals(
            byteArrayOf(
                1,
                1,
                6,
                'c'.code.toByte(),
                'r'.code.toByte(),
                'e'.code.toByte(),
                'a'.code.toByte(),
                't'.code.toByte(),
                'e'.code.toByte(),
                8,
                'k'.code.toByte(),
                'i'.code.toByte(),
                'n'.code.toByte(),
                'e'.code.toByte(),
                't'.code.toByte(),
                'i'.code.toByte(),
                'c'.code.toByte(),
                's'.code.toByte(),
                1,
                0,
                0,
                0,
                1,
                0,
                1,
                0,
                2,
                1,
                3,
            ),
            HostCapabilitySchemaWire.encode(listOf(schema)),
        )
    }

    @Test
    fun `schema rejects invalid identities duplicates and operation limits`() {
        val operation = HostOperationSchema(emptyList(), HostValueType.UNIT, asynchronous = false)

        assertFailsWith<IllegalArgumentException> {
            HostCapabilitySchema(CapabilityIdentity("Create", "kinetics", 1, 0), listOf(operation))
        }
        assertFailsWith<IllegalArgumentException> {
            HostCapabilitySchema(CapabilityIdentity("create", "kinetics", 0, 0), listOf(operation))
        }
        assertFailsWith<IllegalArgumentException> {
            HostOperationSchema(List(HostCapabilityLimits.MAXIMUM_ARGUMENTS + 1) { HostValueType.I32 }, HostValueType.UNIT, false)
        }
        val schema = HostCapabilitySchema(CapabilityIdentity("create", "kinetics", 1, 0), listOf(operation))
        assertFailsWith<IllegalArgumentException> { HostCapabilitySchemaWire.encode(listOf(schema, schema)) }
        val newerMinor = HostCapabilitySchema(CapabilityIdentity("create", "kinetics", 1, 1), listOf(operation))
        assertFailsWith<IllegalArgumentException> { HostCapabilitySchemaWire.encode(listOf(schema, newerMinor)) }
    }
}
