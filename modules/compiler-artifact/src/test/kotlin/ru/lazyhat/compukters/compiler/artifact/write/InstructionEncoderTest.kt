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

package ru.lazyhat.compukters.compiler.artifact.write

import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.CapabilityId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.ImportId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.OrderedScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstructionEncoderTest {
    @Test
    fun `encodes canonical fixture frames and costs`() {
        val cases =
            listOf(
                Instruction.Return(Destination.Unit) to byteArrayOf(0xe3.toByte(), 0, 6, 0, 0xff.toByte(), 0xff.toByte()),
                Instruction.NewObject(RegisterId.of(0u), TypeRef.Local(TypeId.of(0u))) to
                    byteArrayOf(0x30, 0, 7, 0, 0, 0, 0),
                Instruction.Null(RegisterId.of(1u)) to byteArrayOf(0x03, 0, 6, 0, 1, 0),
                Instruction.Branch(RegisterId.of(2u), BlockId.of(1u), BlockId.of(2u)) to
                    byteArrayOf(0xe1.toByte(), 0, 8, 0, 2, 0, 1, 2),
                Instruction.Throw(RegisterId.of(6u)) to byteArrayOf(0xe4.toByte(), 0, 6, 0, 6, 0),
            )

        cases.forEach { (instruction, expected) ->
            assertContentEquals(expected, encodeInstruction(instruction, 64).bytes)
        }
        assertEquals(4u, encodeInstruction(cases[1].first, 64).fixedCost)
        assertEquals(1u, encodeInstruction(cases[3].first, 64).fixedCost)
        assertEquals(2u, encodeInstruction(cases[4].first, 64).fixedCost)
    }

    @Test
    fun `type references use canonical ULEB128`() {
        assertContentEquals(
            byteArrayOf(0x30, 0, 8, 0, 0, 0, 0x80.toByte(), 0x01),
            encodeInstruction(
                Instruction.NewObject(RegisterId.of(0u), TypeRef.Local(TypeId.of(128u))),
                64,
            ).bytes,
        )
    }

    @Test
    fun `direct calls encode optional destination function reference and arguments canonically`() {
        val local =
            encodeInstruction(
                Instruction.Call(
                    Destination.Register(RegisterId.of(1u)),
                    FunctionRef.Local(FunctionId.of(128u)),
                    emptyList(),
                ),
                64,
            )
        val imported =
            encodeInstruction(
                Instruction.Call(
                    Destination.Unit,
                    FunctionRef.Imported(ImportId.of(2u)),
                    listOf(RegisterId.of(3u), RegisterId.of(4u)),
                ),
                64,
            )

        assertContentEquals(byteArrayOf(0x40, 0, 9, 0, 1, 0, 0x80.toByte(), 0x01, 0), local.bytes)
        assertEquals(4u, local.fixedCost)
        assertContentEquals(
            byteArrayOf(
                0x40,
                0,
                16,
                0,
                0xff.toByte(),
                0xff.toByte(),
                0x82.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x08,
                0x02,
                0x03,
                0,
                0x04,
                0,
            ),
            imported.bytes,
        )
        assertEquals(6u, imported.fixedCost)
    }

    @Test
    fun `suspending calls encode resume block after canonical arguments`() {
        val localUnit =
            encodeInstruction(
                Instruction.CallSuspend(
                    Destination.Unit,
                    FunctionRef.Local(FunctionId.of(0u)),
                    emptyList(),
                    BlockId.of(1u),
                ),
                64,
            )
        val importedRegister =
            encodeInstruction(
                Instruction.CallSuspend(
                    Destination.Register(RegisterId.of(5u)),
                    FunctionRef.Imported(ImportId.of(2u)),
                    listOf(RegisterId.of(3u), RegisterId.of(4u)),
                    BlockId.of(128u),
                ),
                64,
            )

        assertContentEquals(
            byteArrayOf(0xe5.toByte(), 0, 9, 0, 0xff.toByte(), 0xff.toByte(), 0, 0, 1),
            localUnit.bytes,
        )
        assertEquals(5u, localUnit.fixedCost)
        assertContentEquals(
            byteArrayOf(
                0xe5.toByte(),
                0,
                18,
                0,
                5,
                0,
                0x82.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x80.toByte(),
                0x08,
                2,
                3,
                0,
                4,
                0,
                0x80.toByte(),
                1,
            ),
            importedRegister.bytes,
        )
        assertEquals(7u, importedRegister.fixedCost)
    }

    @Test
    fun `string concat encodes three registers at fixed cost one`() {
        val encoded =
            encodeInstruction(
                Instruction.StringConcat(RegisterId.of(2u), RegisterId.of(3u), RegisterId.of(4u)),
                64,
            )

        assertContentEquals(byteArrayOf(0x65, 0, 10, 0, 2, 0, 3, 0, 4, 0), encoded.bytes)
        assertEquals(1u, encoded.fixedCost)
    }

    @Test
    fun `move and typed scalar operations encode canonical forms`() {
        val cases =
            listOf(
                Instruction.Move(RegisterId.of(1u), RegisterId.of(2u)) to
                    byteArrayOf(0x01, 0, 8, 0, 1, 0, 2, 0),
                Instruction.AddI32(RegisterId.of(1u), RegisterId.of(2u), RegisterId.of(3u)) to
                    byteArrayOf(0x10, 1, 10, 0, 1, 0, 2, 0, 3, 0),
                Instruction.SubtractI32(RegisterId.of(1u), RegisterId.of(2u), RegisterId.of(3u)) to
                    byteArrayOf(0x11, 1, 10, 0, 1, 0, 2, 0, 3, 0),
                Instruction.Equal(
                    ScalarValueType.CHAR,
                    RegisterId.of(1u),
                    RegisterId.of(2u),
                    RegisterId.of(3u),
                ) to byteArrayOf(0x20, 6, 10, 0, 1, 0, 2, 0, 3, 0),
                Instruction.Less(OrderedScalarValueType.I32, RegisterId.of(1u), RegisterId.of(2u), RegisterId.of(3u)) to
                    byteArrayOf(0x22, 1, 10, 0, 1, 0, 2, 0, 3, 0),
                Instruction.LessOrEqual(OrderedScalarValueType.I32, RegisterId.of(1u), RegisterId.of(2u), RegisterId.of(3u)) to
                    byteArrayOf(0x23, 1, 10, 0, 1, 0, 2, 0, 3, 0),
                Instruction.Greater(OrderedScalarValueType.I32, RegisterId.of(1u), RegisterId.of(2u), RegisterId.of(3u)) to
                    byteArrayOf(0x24, 1, 10, 0, 1, 0, 2, 0, 3, 0),
                Instruction.GreaterOrEqual(OrderedScalarValueType.I32, RegisterId.of(1u), RegisterId.of(2u), RegisterId.of(3u)) to
                    byteArrayOf(0x25, 1, 10, 0, 1, 0, 2, 0, 3, 0),
            )

        cases.forEach { (instruction, expected) ->
            val encoded = encodeInstruction(instruction, 64)
            assertContentEquals(expected, encoded.bytes)
            assertEquals(1u, encoded.fixedCost)
        }
    }

    @Test
    fun `string primitive instructions encode canonical registers`() {
        val cases =
            listOf(
                Instruction.StringLength(RegisterId.of(1u), RegisterId.of(2u)) to
                    byteArrayOf(0x60, 0, 8, 0, 1, 0, 2, 0),
                Instruction.StringGet(RegisterId.of(1u), RegisterId.of(2u), RegisterId.of(3u)) to
                    byteArrayOf(0x61, 0, 10, 0, 1, 0, 2, 0, 3, 0),
                Instruction.StringEquals(RegisterId.of(1u), RegisterId.of(2u), RegisterId.of(3u)) to
                    byteArrayOf(0x62, 0, 10, 0, 1, 0, 2, 0, 3, 0),
                Instruction.StringSubstring(
                    RegisterId.of(1u),
                    RegisterId.of(2u),
                    RegisterId.of(3u),
                    RegisterId.of(4u),
                ) to byteArrayOf(0x66, 0, 12, 0, 1, 0, 2, 0, 3, 0, 4, 0),
            )

        cases.forEach { (instruction, expected) ->
            val encoded = encodeInstruction(instruction, 64)
            assertContentEquals(expected, encoded.bytes)
            assertEquals(1u, encoded.fixedCost)
        }
    }

    @Test
    fun `sync capability calls encode optional destination and variable cost`() {
        val encoded =
            encodeInstruction(
                Instruction.CapabilityCallSync(
                    Destination.Register(RegisterId.of(5u)),
                    CapabilityId.of(128u),
                    129u,
                    listOf(RegisterId.of(3u), RegisterId.of(4u)),
                ),
                64,
            )

        assertContentEquals(
            byteArrayOf(0x51, 0, 15, 0, 5, 0, 0x80.toByte(), 1, 0x81.toByte(), 1, 2, 3, 0, 4, 0),
            encoded.bytes,
        )
        assertEquals(7u, encoded.fixedCost)
    }

    @Test
    fun `async capability calls encode optional destination operation arguments and resume`() {
        val unit =
            encodeInstruction(
                Instruction.CapabilityCallAsync(
                    Destination.Unit,
                    CapabilityId.of(0u),
                    1u,
                    emptyList(),
                    BlockId.of(2u),
                ),
                64,
            )
        val register =
            encodeInstruction(
                Instruction.CapabilityCallAsync(
                    Destination.Register(RegisterId.of(5u)),
                    CapabilityId.of(128u),
                    129u,
                    listOf(RegisterId.of(3u), RegisterId.of(4u)),
                    BlockId.of(128u),
                ),
                64,
            )

        assertContentEquals(
            byteArrayOf(0xe9.toByte(), 0, 10, 0, 0xff.toByte(), 0xff.toByte(), 0, 1, 0, 2),
            unit.bytes,
        )
        assertEquals(6u, unit.fixedCost)
        assertContentEquals(
            byteArrayOf(
                0xe9.toByte(),
                0,
                17,
                0,
                5,
                0,
                0x80.toByte(),
                1,
                0x81.toByte(),
                1,
                2,
                3,
                0,
                4,
                0,
                0x80.toByte(),
                1,
            ),
            register.bytes,
        )
        assertEquals(8u, register.fixedCost)
    }

    @Test
    fun `instruction argument lists are defensive snapshots`() {
        val arguments = mutableListOf(RegisterId.of(1u), RegisterId.of(2u))
        val call = Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(0u)), arguments)
        val suspended = Instruction.CallSuspend(Destination.Unit, FunctionRef.Local(FunctionId.of(0u)), arguments, BlockId.of(0u))
        val capability = Instruction.CapabilityCallAsync(Destination.Unit, CapabilityId.of(0u), 0u, arguments, BlockId.of(0u))
        val syncCapability = Instruction.CapabilityCallSync(Destination.Unit, CapabilityId.of(0u), 0u, arguments)

        arguments += RegisterId.of(3u)

        val expected = listOf(RegisterId.of(1u), RegisterId.of(2u))
        assertEquals(expected, call.arguments)
        assertEquals(expected, suspended.arguments)
        assertEquals(expected, capability.arguments)

        listOf(call.arguments, suspended.arguments, capability.arguments, syncCapability.arguments).forEach { exposedArguments ->
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (exposedArguments as MutableList<RegisterId>) += RegisterId.of(4u)
            }
        }
    }
}
