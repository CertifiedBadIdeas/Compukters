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

package ru.lazyhat.compukters.compiler.artifact.analysis

import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalAtom
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalShape
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueComponent
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TemporaryRegistersTest {
    @Test
    fun `reuses sequential temporaries without aliasing an instruction input and result`() {
        val source =
            module(
                List(4) { scalar() },
                listOf(
                    listOf(
                        Instruction.Const(reg(0), ConstantId.of(0u)),
                        Instruction.Move(reg(1), reg(0)),
                        Instruction.Const(reg(2), ConstantId.of(0u)),
                        Instruction.Add(reg(3), reg(1), reg(2)),
                        Instruction.Return(Destination.Register(reg(3))),
                    ),
                ),
            )
        val compact = TemporaryRegisters.compact(source)
        val instructions = compact.blocks.single().instructions
        assertEquals(
            3,
            compact.functions
                .single()
                .values.size,
        )
        assertEquals((instructions[0] as Instruction.Const).destination, (instructions[2] as Instruction.Const).destination)
        val add = instructions[3] as Instruction.Add
        assertNotEquals(add.left, add.right)
        assertNotEquals(add.destination, add.left)
        assertNotEquals(add.destination, add.right)
        assertEquals(compact, TemporaryRegisters.compact(compact))
    }

    @Test
    fun `keeps parameters in their original dedicated slots`() {
        val source =
            module(
                List(3) { scalar() },
                listOf(
                    listOf(
                        Instruction.Move(reg(2), reg(0)),
                        Instruction.Return(Destination.Register(reg(2))),
                    ),
                ),
                parameters = 2u,
            )
        val compact = TemporaryRegisters.compact(source)
        assertEquals(
            3,
            compact.functions
                .single()
                .values.size,
        )
        assertEquals(source.blocks, compact.blocks)
        assertEquals(2u, compact.functions.single().parameterCount)
    }

    @Test
    fun `does not combine different semantic types or physical shapes`() {
        val aggregate = FunctionValue(ValueType.I32, PhysicalShape(listOf(PhysicalAtom.I32, PhysicalAtom.I32)))
        val source =
            module(
                listOf(scalar(), scalar(ValueType.Bool), aggregate, scalar(ValueType.I64)),
                listOf(
                    listOf(
                        Instruction.Const(reg(0), ConstantId.of(0u)),
                        Instruction.Const(reg(1), ConstantId.of(0u)),
                        Instruction.Const(reg(2), ConstantId.of(0u)),
                        Instruction.Const(reg(3), ConstantId.of(0u)),
                        Instruction.Return(Destination.Unit),
                    ),
                ),
            )
        assertEquals(
            source.functions.single().values,
            TemporaryRegisters
                .compact(source)
                .functions
                .single()
                .values,
        )
    }

    @Test
    fun `keeps loop carried values alive across back edges`() {
        val source =
            module(
                List(3) { scalar() },
                listOf(
                    listOf(Instruction.Const(reg(0), ConstantId.of(0u)), Instruction.Jump(block(1))),
                    listOf(
                        Instruction.Const(reg(1), ConstantId.of(0u)),
                        Instruction.Add(reg(2), reg(0), reg(1)),
                        Instruction.Move(reg(0), reg(2)),
                        Instruction.Jump(block(1)),
                    ),
                ),
            )
        val compact = TemporaryRegisters.compact(source)
        val add = compact.blocks[1].instructions[1] as Instruction.Add
        assertEquals(
            3,
            compact.functions
                .single()
                .values.size,
        )
        assertNotEquals(add.left, add.right)
        assertNotEquals(add.left, add.destination)
    }

    @Test
    fun `can share temporaries in alternative branches`() {
        val source =
            module(
                listOf(scalar(ValueType.Bool), scalar(), scalar()),
                listOf(
                    listOf(Instruction.Branch(reg(0), block(1), block(2))),
                    listOf(Instruction.Const(reg(1), ConstantId.of(0u)), Instruction.Return(Destination.Register(reg(1)))),
                    listOf(Instruction.Const(reg(2), ConstantId.of(0u)), Instruction.Return(Destination.Register(reg(2)))),
                ),
                parameters = 1u,
            )
        val compact = TemporaryRegisters.compact(source)
        assertEquals(
            2,
            compact.functions
                .single()
                .values.size,
        )
        assertEquals(
            (compact.blocks[1].instructions[0] as Instruction.Const).destination,
            (compact.blocks[2].instructions[0] as Instruction.Const).destination,
        )
    }

    @Test
    fun `keeps values across suspension distinct from the pending result`() {
        val source =
            module(
                List(4) { scalar() },
                listOf(
                    listOf(
                        Instruction.Const(reg(0), ConstantId.of(0u)),
                        Instruction.Const(reg(1), ConstantId.of(0u)),
                        Instruction.CallSuspend(
                            Destination.Register(reg(2)),
                            FunctionRef.Local(FunctionId.of(0u)),
                            listOf(reg(1)),
                            block(1),
                        ),
                    ),
                    listOf(Instruction.Add(reg(3), reg(0), reg(2)), Instruction.Return(Destination.Register(reg(3)))),
                ),
            )
        val compact = TemporaryRegisters.compact(source)
        val call = compact.blocks[0].instructions.last() as Instruction.CallSuspend
        val add = compact.blocks[1].instructions.first() as Instruction.Add
        assertNotEquals(add.left, call.destination.let { (it as Destination.Register).id })
        assertNotEquals(call.arguments.single(), (call.destination as Destination.Register).id)
        assertNotEquals(add.left, call.arguments.single())
    }

    @Test
    fun `retains handler live values and gives exception destinations dedicated storage`() {
        val ref = scalar(ValueType.Ref(false, TypeRef.Local(TypeId.of(0u))))
        val base =
            module(
                listOf(ref, ref, ref, scalar(ValueType.Bool)),
                listOf(
                    listOf(
                        Instruction.Null(reg(0)),
                        Instruction.Call(Destination.Register(reg(1)), FunctionRef.Local(FunctionId.of(0u)), emptyList()),
                        Instruction.Return(Destination.Unit),
                    ),
                    listOf(Instruction.RefEqual(reg(3), reg(0), reg(2)), Instruction.Return(Destination.Unit)),
                ),
            )
        val source =
            base.copy(
                functions = base.functions.map { it.copy(exceptionCount = 1u) },
                exceptions = listOf(ExceptionEntry(FunctionId.of(0u), block(0), 1u, null, block(1), reg(2))),
            )
        val compact = TemporaryRegisters.compact(source)
        val call = compact.blocks[0].instructions[1] as Instruction.Call
        val comparison = compact.blocks[1].instructions[0] as Instruction.RefEqual
        assertNotEquals(comparison.left, (call.destination as Destination.Register).id)
        assertNotEquals(comparison.left, comparison.right)
        assertNotEquals(comparison.right, (call.destination as Destination.Register).id)
        assertEquals(comparison.right, compact.exceptions.single().exceptionRegister)
        assertEquals(
            listOf(ValueComponent(comparison.left, 0u)),
            compact.functions
                .single()
                .safepointRoots[1]
                .references,
        )
    }

    @Test
    fun `regenerates reference roots after reusing multi component values`() {
        val ref =
            FunctionValue(ValueType.Ref(false, TypeRef.Local(TypeId.of(0u))), PhysicalShape(listOf(PhysicalAtom.I32, PhysicalAtom.REF32)))
        val source =
            module(
                listOf(ref, ref),
                listOf(
                    listOf(
                        Instruction.NewObject(reg(0), TypeRef.Local(TypeId.of(0u))),
                        Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(0u)), listOf(reg(0))),
                        Instruction.NewObject(reg(1), TypeRef.Local(TypeId.of(0u))),
                        Instruction.Return(Destination.Register(reg(1))),
                    ),
                ),
            )
        val compact = TemporaryRegisters.compact(source)
        assertEquals(
            1,
            compact.functions
                .single()
                .values.size,
        )
        assertEquals(
            listOf(ValueComponent(reg(0), 1u)),
            compact.functions
                .single()
                .safepointRoots[1]
                .references,
        )
        assertEquals(
            emptyList(),
            compact.functions
                .single()
                .safepointRoots[2]
                .references,
        )
        assertEquals(
            listOf(ValueComponent(reg(0), 1u)),
            compact.functions
                .single()
                .safepointRoots[3]
                .references,
        )
    }

    private fun scalar(type: ValueType = ValueType.I32) = FunctionValue.scalar(type)

    private fun reg(value: Int) = RegisterId.of(value.toUInt())

    private fun block(value: Int) = BlockId.of(value.toUInt())

    private fun module(
        values: List<FunctionValue>,
        instructions: List<List<Instruction>>,
        parameters: UInt = 0u,
    ): Module =
        Module(
            name = StringId.of(0u),
            kind = ModuleKind.APPLICATION,
            strings = listOf(MetadataText.of("test")),
            functions =
                listOf(
                    Function(
                        null,
                        StringId.of(0u),
                        TypeRef.Local(TypeId.of(0u)),
                        setOf(FunctionFlag.STATIC),
                        values,
                        parameters,
                        block(0),
                        instructions.size.toUInt(),
                        0u,
                        0u,
                    ),
                ),
            blocks = instructions.map { Block(FunctionId.of(0u), false, it) },
        )
}
