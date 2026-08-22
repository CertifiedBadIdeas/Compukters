/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukters.compiler.artifact.write

import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
