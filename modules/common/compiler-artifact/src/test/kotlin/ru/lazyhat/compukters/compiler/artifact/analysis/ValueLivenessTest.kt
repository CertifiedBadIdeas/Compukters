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
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals

class ValueLivenessTest {
    @Test
    fun `normal continuation preserves a register defined only on the exceptional edge`() {
        val source =
            module(
                blocks =
                    listOf(
                        listOf(
                            Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(0u)), emptyList()),
                            Instruction.Jump(block(1)),
                        ),
                        listOf(Instruction.RefEqual(reg(2), reg(0), reg(1)), Instruction.Return(Destination.Unit)),
                    ),
                exceptions = listOf(ExceptionEntry(FunctionId.of(0u), block(0), 1u, null, block(1), reg(1))),
            )
        val boundaries = ValueLiveness.derive(source, source.functions.single())
        assertEquals(setOf(reg(0), reg(1)), boundaries[0].before)
        assertEquals(setOf(reg(0), reg(1)), boundaries[0].after)
        assertEquals(setOf(reg(0), reg(1)), boundaries[1].before)
        assertEquals(emptySet(), boundaries.last().after)
    }

    @Test
    fun `exceptional edge defines its own catch register without losing another handlers input`() {
        val source =
            module(
                blocks =
                    listOf(
                        listOf(
                            Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(0u)), emptyList()),
                            Instruction.Return(Destination.Unit),
                        ),
                        listOf(Instruction.Return(Destination.Register(reg(0)))),
                        listOf(Instruction.Return(Destination.Register(reg(1)))),
                    ),
                exceptions =
                    listOf(
                        ExceptionEntry(FunctionId.of(0u), block(0), 1u, null, block(1), reg(1)),
                        ExceptionEntry(FunctionId.of(0u), block(0), 1u, null, block(2), reg(0)),
                    ),
            )
        val boundaries = ValueLiveness.derive(source, source.functions.single())
        assertEquals(setOf(reg(0), reg(1)), boundaries[0].before)
        assertEquals(setOf(reg(0), reg(1)), boundaries[0].after)
        assertEquals(emptySet(), boundaries[1].before)
    }

    @Test
    fun `loop propagates values across multiple machine words and kills the call result before its definition`() {
        val inputs = (0 until 130).map(::reg).toSet()
        val source =
            module(
                blocks =
                    listOf(
                        listOf(Instruction.Jump(block(1))),
                        listOf(
                            Instruction.Call(Destination.Register(reg(130)), FunctionRef.Local(FunctionId.of(0u)), inputs.toList()),
                            Instruction.Branch(reg(130), block(1), block(2)),
                        ),
                        listOf(Instruction.Return(Destination.Register(reg(129)))),
                    ),
                valueCount = 131,
            )
        val boundaries = ValueLiveness.derive(source, source.functions.single())
        assertEquals(inputs, boundaries[0].before)
        assertEquals(inputs, boundaries[1].before)
        assertEquals(inputs + reg(130), boundaries[1].after)
        assertEquals(inputs, boundaries[2].after)
        assertEquals(setOf(reg(129)), boundaries[3].before)
        assertEquals(emptySet(), boundaries[3].after)
    }

    @Test
    fun `only reachable instructions in the selected function contribute boundaries`() {
        val source =
            module(
                blocks =
                    listOf(
                        listOf(Instruction.Return(Destination.Register(reg(1)))),
                        listOf(Instruction.Jump(block(3))),
                        listOf(Instruction.Return(Destination.Register(reg(2)))),
                        listOf(Instruction.Return(Destination.Register(reg(0)))),
                        listOf(Instruction.Return(Destination.Register(reg(1)))),
                    ),
                firstBlock = 1,
                blockCount = 3,
            )
        val boundaries = ValueLiveness.derive(source, source.functions.single())
        assertEquals(listOf(block(1), block(3)), boundaries.map(LiveBoundary::block))
        assertEquals(listOf(setOf(reg(0)), setOf(reg(0))), boundaries.map(LiveBoundary::before))
    }

    @Test
    fun `an empty entry block does not implicitly reach later blocks`() {
        val source = module(listOf(emptyList(), listOf(Instruction.Return(Destination.Register(reg(0))))))
        assertEquals(emptyList(), ValueLiveness.derive(source, source.functions.single()))
    }

    @Test
    fun `jump outside the selected function does not contribute liveness`() {
        val source =
            module(
                listOf(listOf(Instruction.Jump(block(1))), listOf(Instruction.Return(Destination.Register(reg(0))))),
                blockCount = 1,
            )
        val boundary = ValueLiveness.derive(source, source.functions.single()).single()
        assertEquals(emptySet(), boundary.before)
        assertEquals(emptySet(), boundary.after)
    }

    @Test
    fun `sparse register identities do not change the logical live sets`() {
        val source = module(listOf(listOf(Instruction.Move(reg(60000), reg(40000)), Instruction.Return(Destination.Register(reg(60000))))))
        val boundaries = ValueLiveness.derive(source, source.functions.single())
        assertEquals(setOf(reg(40000)), boundaries[0].before)
        assertEquals(setOf(reg(60000)), boundaries[0].after)
        assertEquals(setOf(reg(60000)), boundaries[1].before)
    }

    private fun reg(value: Int) = RegisterId.of(value.toUInt())

    private fun block(value: Int) = BlockId.of(value.toUInt())

    private fun module(
        blocks: List<List<Instruction>>,
        exceptions: List<ExceptionEntry> = emptyList(),
        valueCount: Int = 3,
        firstBlock: Int = 0,
        blockCount: Int = blocks.size - firstBlock,
    ): Module =
        Module(
            name = StringId.of(0u),
            kind = ModuleKind.APPLICATION,
            strings = listOf(MetadataText.of("test")),
            functions =
                listOf(
                    Function(
                        owner = null,
                        name = StringId.of(0u),
                        signature = TypeRef.Local(TypeId.of(0u)),
                        flags = setOf(FunctionFlag.STATIC),
                        values = List(valueCount) { FunctionValue.scalar(ValueType.I32) },
                        parameterCount = 0u,
                        firstBlock = block(firstBlock),
                        blockCount = blockCount.toUInt(),
                        firstException = 0u,
                        exceptionCount = exceptions.size.toUInt(),
                    ),
                ),
            blocks = blocks.map { Block(FunctionId.of(0u), false, it) },
            exceptions = exceptions,
        )
}
