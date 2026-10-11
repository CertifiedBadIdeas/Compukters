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

@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.lazyhat.compukters.compiler.artifact.write

import ru.lazyhat.compukters.compiler.artifact.analysis.ReferenceLiveness
import ru.lazyhat.compukters.compiler.artifact.model.*
import ru.lazyhat.compukters.compiler.artifact.read.ArtifactReader
import kotlin.test.*

class MatrixInstructionsTest {
    @Test
    fun `all matrix selectors round trip with bounded matching costs`() {
        for (operation in MatrixOperation.entries) {
            val artifact = fixture(operation)
            val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
            val original =
                artifact.modules
                    .single()
                    .blocks
                    .single()
                    .instructions
            assertEquals(
                original,
                ArtifactReader
                    .read(bytes)
                    .modules
                    .single()
                    .blocks
                    .single()
                    .instructions,
            )
            val instruction = original.filterIsInstance<Instruction.MatrixMath>().single()
            assertEquals(operation.fixedCost, encodeInstruction(instruction, 64).fixedCost)
        }
    }

    @Test
    fun `reader rejects unknown selectors forms and wrong encoded arity`() {
        for (operation in listOf(MatrixOperation.TRANSPOSE, MatrixOperation.MULTIPLY_MATRIX)) {
            val artifact = fixture(operation)
            val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
            val instruction =
                artifact.modules
                    .single()
                    .blocks
                    .single()
                    .instructions
                    .filterIsInstance<Instruction.MatrixMath>()
                    .single()
            val frame = encodeInstruction(instruction, 64).bytes
            val offset = (0..bytes.size - frame.size).single { start -> frame.indices.all { bytes[start + it] == frame[it] } }
            for ((relative, value) in listOf(1 to 1, 4 to 0, 4 to 6, 4 to if (operation == MatrixOperation.TRANSPOSE) 2 else 1)) {
                val invalid = bytes.copyOf()
                invalid[offset + relative] = value.toByte()
                val payloadEnd = invalid.size - 32
                val digest =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(invalid.copyOfRange(0, payloadEnd))
                digest.copyInto(invalid, payloadEnd)
                assertFails { ArtifactReader.read(invalid) }
            }
        }
    }

    @Test
    fun `matrix verifier rejects old ABI wrong arity layout and uninitialized inputs`() {
        for (operation in MatrixOperation.entries) {
            val artifact = fixture(operation)
            assertTrue(validateArtifact(artifact, ArtifactWriteLimits()).isEmpty())
            assertTrue(
                validateArtifact(artifact.copy(minimumRuntimeAbi = AbiVersion(1u, 16u)), ArtifactWriteLimits()).any {
                    "1.17" in
                        it.detail
                },
            )
            val module = artifact.modules.single()
            val block = module.blocks.single()
            val instruction = block.instructions.filterIsInstance<Instruction.MatrixMath>().single()
            val wrongArity = instruction.copy(right = if (instruction.right == null) r(2u) else null)
            val mutations =
                listOf(
                    module.copy(
                        blocks =
                            listOf(
                                block.copy(
                                    instructions =
                                        block.instructions.map {
                                            if (it ==
                                                instruction
                                            ) {
                                                wrongArity
                                            } else {
                                                it
                                            }
                                        },
                                ),
                            ),
                    ),
                    module.copy(
                        types =
                            module.types.map {
                                if (it is NominalType.InlineValue) {
                                    it.copy(
                                        components = it.components.dropLast(1),
                                    )
                                } else {
                                    it
                                }
                            },
                    ),
                    module.copy(
                        types =
                            module.types.map {
                                if (it is NominalType.InlineValue) it.copy(components = List(it.components.size) { ValueType.I32 }) else it
                            },
                    ),
                    module.copy(
                        blocks = listOf(block.copy(instructions = block.instructions.filterNot { it is Instruction.InlineConstruct })),
                    ),
                )
            for (invalid in mutations) assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(modules = listOf(invalid))))
        }
    }

    private fun r(id: UInt) = RegisterId.of(id)

    private fun fixture(operation: MatrixOperation): Artifact {
        val rightCount = operation.rightComponents ?: 16
        val instruction = Instruction.MatrixMath(operation, r(3u), r(1u), if (operation.rightComponents != null) r(2u) else null)
        val instructions =
            listOf(
                Instruction.Const(r(0u), ConstantId.of(0u)),
                Instruction.InlineConstruct(r(1u), List(16) { r(0u) }),
                Instruction.InlineConstruct(r(2u), List(rightCount) { r(0u) }),
                instruction,
                Instruction.Return(Destination.Unit),
            )
        val original = minimalArtifact(instructions)
        val module = original.modules.single()
        val counts = listOf(16, rightCount, operation.resultComponents)
        return ReferenceLiveness.derive(
            original.copy(
                minimumRuntimeAbi = AbiVersion(1u, 17u),
                manifest = Manifest.minimal(instructions.sumOf { encodeInstruction(it, 64).fixedCost }),
                modules =
                    listOf(
                        module.copy(
                            types = module.types + counts.map { NominalType.InlineValue(StringId.of(1u), List(it) { ValueType.F32 }) },
                            constants = listOf(Constant.F32(1f.toBits().toUInt())),
                            functions =
                                listOf(
                                    module.functions.single().copy(
                                        values =
                                            listOf(FunctionValue.scalar(ValueType.F32)) +
                                                counts.mapIndexed {
                                                    index,
                                                    count,
                                                    ->
                                                    FunctionValue(
                                                        ValueType.Inline(TypeRef.Local(TypeId.of((index + 1).toUInt()))),
                                                        PhysicalShape(List(count) { PhysicalAtom.F32 }),
                                                    )
                                                },
                                    ),
                                ),
                        ),
                    ),
            ),
        )
    }
}
