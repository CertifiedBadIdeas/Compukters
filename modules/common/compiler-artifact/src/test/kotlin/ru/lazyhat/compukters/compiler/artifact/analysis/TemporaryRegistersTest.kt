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
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntry
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntryId
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
import kotlin.test.assertTrue

class TemporaryRegistersTest {
    @Test
    fun `eliminates a dying source copy while keeping arithmetic inputs and result distinct`() {
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
        assertEquals(4, instructions.size)
        val add = instructions[2] as Instruction.Add
        assertEquals((instructions[0] as Instruction.Const).destination, add.left)
        assertEquals((instructions[1] as Instruction.Const).destination, add.right)
        assertNotEquals(add.left, add.right)
        assertNotEquals(add.destination, add.left)
        assertNotEquals(add.destination, add.right)
        assertEquals(compact, TemporaryRegisters.compact(compact))
    }

    @Test
    fun `retains a copy when its source is overwritten before the copied value is read`() {
        val source =
            module(
                List(3) { scalar() },
                listOf(
                    listOf(
                        Instruction.Const(reg(0), ConstantId.of(0u)),
                        Instruction.Move(reg(1), reg(0)),
                        Instruction.Const(reg(0), ConstantId.of(1u)),
                        Instruction.Add(reg(2), reg(0), reg(1)),
                        Instruction.Return(Destination.Register(reg(2))),
                    ),
                ),
            )
        val compact = TemporaryRegisters.compact(source)
        val move = compact.blocks.single().instructions[1] as Instruction.Move
        val add = compact.blocks.single().instructions[3] as Instruction.Add
        assertNotEquals(move.source, move.destination)
        assertNotEquals(add.left, add.right)
    }

    @Test
    fun `relocates debug boundaries and preserves inline parents when copies disappear`() {
        val base =
            module(
                List(2) { scalar() },
                listOf(
                    listOf(
                        Instruction.Const(reg(0), ConstantId.of(0u)),
                        Instruction.Move(reg(1), reg(0)),
                        Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(0u)), listOf(reg(1))),
                        Instruction.Return(Destination.Register(reg(1))),
                    ),
                ),
            )
        val source =
            base.copy(
                debug =
                    (0u..3u).map { index ->
                        DebugEntry(
                            FunctionId.of(0u),
                            block(0),
                            index,
                            index,
                            index + 1u,
                            when (index) {
                                0u -> null
                                3u -> DebugEntryId.of(2u)
                                else -> DebugEntryId.of(0u)
                            },
                            MetadataText.of("test.kt"),
                            index + 1u,
                            1u,
                        )
                    },
            )
        val compact = TemporaryRegisters.compact(source)
        assertEquals(listOf(0u, 1u, 2u), compact.debug.map { it.instruction })
        assertEquals(listOf(null, DebugEntryId.of(0u), DebugEntryId.of(1u)), compact.debug.map { it.inlineParent })
        assertEquals(listOf(1u, 3u, 4u), compact.debug.map { it.sourceLine })
        assertEquals(compact, TemporaryRegisters.compact(compact))

        val anchored =
            source.copy(
                debug =
                    source.debug.mapIndexed { index, entry ->
                        when (index) {
                            3 -> entry.copy(inlineParent = DebugEntryId.of(1u))
                            else -> entry
                        }
                    },
            )
        val anchoredCompact = TemporaryRegisters.compact(anchored)
        assertEquals(
            4,
            anchoredCompact.blocks
                .single()
                .instructions.size,
        )
        assertEquals(anchored.debug, anchoredCompact.debug)
    }

    @Test
    fun `coalesces multi component copies and rebuilds reference roots across suspension`() {
        val inline =
            FunctionValue(ValueType.Inline(TypeRef.Local(TypeId.of(0u))), PhysicalShape(listOf(PhysicalAtom.I32, PhysicalAtom.REF32)))
        val source =
            module(
                listOf(inline, inline, scalar()),
                listOf(
                    listOf(
                        Instruction.Const(reg(0), ConstantId.of(0u)),
                        Instruction.Move(reg(1), reg(0)),
                        Instruction.CallSuspend(
                            Destination.Register(reg(2)),
                            FunctionRef.Local(FunctionId.of(0u)),
                            listOf(reg(1)),
                            block(1),
                        ),
                    ),
                    listOf(Instruction.Return(Destination.Register(reg(1)))),
                ),
            )
        val compact = TemporaryRegisters.compact(source)
        assertTrue(compact.blocks.flatMap { it.instructions }.none { it is Instruction.Move })
        val value = (compact.blocks[1].instructions.single() as Instruction.Return).value as Destination.Register
        val call = compact.blocks[0].instructions.last() as Instruction.CallSuspend
        assertEquals(listOf(value.id), call.arguments)
        assertEquals(
            listOf(ValueComponent(value.id, 1u)),
            compact.functions
                .single()
                .safepointRoots
                .single { it.block == block(0) && it.instructionBoundary == 1u }
                .references,
        )
        assertEquals(compact, TemporaryRegisters.compact(compact))
    }

    @Test
    fun `retains a source needed only by an exception handler after a copy`() {
        val base =
            module(
                List(3) { scalar() },
                listOf(
                    listOf(
                        Instruction.Const(reg(0), ConstantId.of(0u)),
                        Instruction.Move(reg(1), reg(0)),
                        Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(0u)), emptyList()),
                        Instruction.Return(Destination.Register(reg(1))),
                    ),
                    listOf(Instruction.Return(Destination.Register(reg(0)))),
                ),
            )
        val source =
            base.copy(
                functions = base.functions.map { it.copy(exceptionCount = 1u) },
                exceptions = listOf(ExceptionEntry(FunctionId.of(0u), block(0), 1u, null, block(1), reg(2))),
            )
        val compact = TemporaryRegisters.compact(source)
        val move = compact.blocks[0].instructions[1] as Instruction.Move
        assertNotEquals(move.source, move.destination)
        assertEquals(
            move.source,
            (compact.blocks[1].instructions.single() as Instruction.Return).value.let { (it as Destination.Register).id },
        )
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
