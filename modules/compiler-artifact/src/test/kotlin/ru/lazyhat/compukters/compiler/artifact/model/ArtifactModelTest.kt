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

package ru.lazyhat.compukters.compiler.artifact.model

import ru.lazyhat.compukters.compiler.artifact.pool.ConstantPoolBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ArtifactModelTest {
    @Test
    fun `logical block owns typed instructions but no physical encoding`() {
        val block =
            Block(
                owner = FunctionId.of(0u),
                loopHeaderSafepoint = false,
                instructions = listOf(Instruction.Return(Destination.Unit)),
            )

        assertEquals(1, block.instructions.size)
        assertFalse(Block::class.java.declaredFields.any { it.name in setOf("byteLength", "declaredFixedCost", "codeOffset") })
    }

    @Test
    fun `constant pool preserves raw floating point bits and canonical order`() {
        val builder = ConstantPoolBuilder()
        val nanA = builder.intern(Constant.F32(0x7fc0_0001u))
        val integer = builder.intern(Constant.I32(0))
        val nanB = builder.intern(Constant.F32(0x7fc0_0002u))
        val duplicate = builder.intern(Constant.I32(0))

        val frozen = builder.freeze()

        assertEquals(listOf(Constant.I32(0), Constant.F32(0x7fc0_0001u), Constant.F32(0x7fc0_0002u)), frozen.records)
        assertEquals(frozen.idOf(integer), frozen.idOf(duplicate))
        assertEquals(1u, frozen.idOf(nanA).value)
        assertEquals(2u, frozen.idOf(nanB).value)
    }

    @Test
    fun `vector A model contains only logical entry and records`() {
        val artifact =
            Artifact(
                manifest = Manifest.minimal(),
                entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
                modules =
                    listOf(
                        Module(
                            name = StringId.of(0u),
                            kind = ModuleKind.APPLICATION,
                            strings = listOf(MetadataText.of("app"), MetadataText.of("entry")),
                            types =
                                listOf(
                                    NominalType.Function(
                                        name = StringId.of(1u),
                                        suspending = false,
                                        result = ValueType.Unit,
                                        parameters = emptyList(),
                                    ),
                                ),
                            functions =
                                listOf(
                                    Function(
                                        owner = null,
                                        name = StringId.of(1u),
                                        signature = TypeRef.Local(TypeId.of(0u)),
                                        flags = setOf(FunctionFlag.STATIC),
                                        registers = emptyList(),
                                        parameterCount = 0u,
                                        firstBlock = BlockId.of(0u),
                                        blockCount = 1u,
                                        firstException = 0u,
                                        exceptionCount = 0u,
                                    ),
                                ),
                            blocks =
                                listOf(
                                    Block(
                                        owner = FunctionId.of(0u),
                                        loopHeaderSafepoint = false,
                                        instructions = listOf(Instruction.Return(Destination.Unit)),
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(ModuleKind.APPLICATION, artifact.modules.single().kind)
        assertEquals(Instruction.Return(Destination.Unit), artifact.modules.single().blocks.single().instructions.single())
    }
}
