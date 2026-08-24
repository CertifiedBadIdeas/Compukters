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
        assertEquals(
            Instruction.Return(Destination.Unit),
            artifact.modules
                .single()
                .blocks
                .single()
                .instructions
                .single(),
        )
    }
}
