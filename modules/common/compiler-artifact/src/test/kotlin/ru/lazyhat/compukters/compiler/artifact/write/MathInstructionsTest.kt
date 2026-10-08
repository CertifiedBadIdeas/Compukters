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

class MathInstructionsTest {
    @Test
    fun `math selectors and costs round trip for both floating widths`() {
        for (type in listOf(ScalarValueType.F32, ScalarValueType.F64)) {
            val instructions =
                MathUnaryOperation.entries.map { Instruction.MathUnary(type, it, r(2u), r(0u)) } +
                    MathBinaryOperation.entries.map { Instruction.MathBinary(type, it, r(2u), r(0u), r(1u)) }
            for (instruction in instructions) {
                val artifact = mathArtifact(instruction, type)
                val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact))
                val read = ArtifactReader.read(encoded.bytes)
                assertEquals(
                    instruction,
                    read.modules
                        .single()
                        .blocks
                        .single()
                        .instructions[2],
                )
                val expectedCost =
                    when (instruction) {
                        is Instruction.MathUnary -> instruction.operation.fixedCost
                        is Instruction.MathBinary -> instruction.operation.fixedCost
                        else -> error("not math")
                    }
                assertEquals(expectedCost, encodeInstruction(instruction, 64).fixedCost)
            }
        }
    }

    @Test
    fun `math requires ABI 1 16 and homogeneous floating registers`() {
        val unary = Instruction.MathUnary(ScalarValueType.F64, MathUnaryOperation.SQRT, r(2u), r(0u))
        val binary = Instruction.MathBinary(ScalarValueType.F64, MathBinaryOperation.POW, r(2u), r(0u), r(1u))
        for (instruction in listOf(unary, binary)) {
            val artifact = mathArtifact(instruction, ScalarValueType.F64)
            val errors = validateArtifact(artifact, ArtifactWriteLimits())
            assertTrue(errors.isEmpty(), errors.toString())
            assertTrue(
                validateArtifact(artifact.copy(minimumRuntimeAbi = AbiVersion(1u, 15u)), ArtifactWriteLimits())
                    .any { "1.16" in it.detail },
            )
            val module = artifact.modules.single()
            val function = module.functions.single()
            val invalid =
                artifact.copy(
                    modules =
                        listOf(
                            module.copy(
                                functions =
                                    listOf(
                                        function.copy(
                                            values =
                                                function.values.mapIndexed { index, value ->
                                                    if (index ==
                                                        2
                                                    ) {
                                                        FunctionValue.scalar(ValueType.F32)
                                                    } else {
                                                        value
                                                    }
                                                },
                                        ),
                                    ),
                            ),
                        ),
                )
            assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { "math requires matching" in it.detail })
        }
        val invalidForm = mathArtifact(unary.copy(type = ScalarValueType.I32), ScalarValueType.F64)
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(invalidForm))
    }

    @Test
    fun `math source registers participate in initialization analysis`() {
        val artifact = mathArtifact(Instruction.MathUnary(ScalarValueType.F64, MathUnaryOperation.SQRT, r(2u), r(0u)), ScalarValueType.F64)
        val module = artifact.modules.single()
        val block = module.blocks.single()
        val invalid = artifact.copy(modules = listOf(module.copy(blocks = listOf(block.copy(instructions = block.instructions.drop(1))))))
        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { "initialized" in it.detail })
    }

    private fun r(id: UInt) = RegisterId.of(id)

    private fun mathArtifact(
        instruction: Instruction,
        type: ScalarValueType,
    ): Artifact {
        val cost = encodeInstruction(instruction, 64).fixedCost + 3u
        val original =
            minimalArtifact(
                listOf(
                    Instruction.Const(r(0u), ConstantId.of(0u)),
                    Instruction.Const(r(1u), ConstantId.of(1u)),
                    instruction,
                    Instruction.Return(Destination.Unit),
                ),
            )
        val module = original.modules.single()
        val valueType = if (type == ScalarValueType.F32) ValueType.F32 else ValueType.F64
        val constants =
            if (type == ScalarValueType.F32) {
                listOf(Constant.F32(2.0f.toBits().toUInt()), Constant.F32(3.0f.toBits().toUInt()))
            } else {
                listOf(Constant.F64(2.0.toBits().toULong()), Constant.F64(3.0.toBits().toULong()))
            }
        return ReferenceLiveness.derive(
            original.copy(
                minimumRuntimeAbi = AbiVersion(1u, 16u),
                manifest = Manifest.minimal(cost),
                modules =
                    listOf(
                        module.copy(
                            constants = constants,
                            functions = listOf(module.functions.single().copy(values = List(3) { FunctionValue.scalar(valueType) })),
                        ),
                    ),
            ),
        )
    }
}
