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

import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Capability
import ru.lazyhat.compukters.compiler.artifact.model.CapabilityId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.Import
import ru.lazyhat.compukters.compiler.artifact.model.ImportId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArtifactValidatorTest {
    @Test
    fun `invalid entry produces a stable bounded diagnostic without bytes`() {
        val artifact =
            Artifact(
                manifest = Manifest.minimal(),
                entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
                modules = emptyList(),
            )

        val errors = validateArtifact(artifact, ArtifactWriteLimits(diagnostics = 1))

        assertEquals(1, errors.size)
        assertEquals(ArtifactWriteErrorCode.BAD_REFERENCE, errors.single().code)
        assertTrue(errors.single().detail.contains("entry module"))
    }

    @Test
    fun `missing block terminator is rejected at its logical location`() {
        val artifact = minimalArtifact(instructions = emptyList())

        val error = validateArtifact(artifact, ArtifactWriteLimits()).first { it.detail.contains("terminator") }

        assertEquals(ArtifactWriteErrorCode.INCONSISTENT_RANGE, error.code)
        assertEquals(0u, error.location?.module)
        assertEquals(0u, error.location?.record)
    }

    @Test
    fun `block and output limits are checked before encoding`() {
        val errors = validateArtifact(minimalArtifact(), ArtifactWriteLimits(blocks = 0, artifactBytes = 100))
        assertTrue(errors.any { it.code == ArtifactWriteErrorCode.LIMIT_EXCEEDED && it.detail.contains("blocks") })
    }

    @Test
    fun `semantic feature bits exactly match tables functions and instructions`() {
        val artifact =
            executableArtifact(
                Instruction.CapabilityCallAsync(Destination.Unit, CapabilityId.of(0u), 0u, emptyList(), BlockId.of(1u)),
            )
        val expected = setOf(SemanticFeature.COROUTINES, SemanticFeature.CAPABILITIES, SemanticFeature.MODULE_IMPORTS)

        assertEquals(emptyList(), validateArtifact(artifact.copy(semanticFeatures = expected), ArtifactWriteLimits()))
        assertTrue(
            validateArtifact(
                artifact.copy(semanticFeatures = expected - SemanticFeature.CAPABILITIES),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("semantic feature") },
        )
        assertTrue(
            validateArtifact(
                artifact.copy(semanticFeatures = expected + SemanticFeature.EXCEPTIONS),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("semantic feature") },
        )
    }

    @Test
    fun `imports require the exact target module semantic hash`() {
        val artifact =
            executableArtifact(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Imported(ImportId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
            )
        val source = artifact.modules[0]
        val stale =
            artifact.copy(
                modules =
                    listOf(
                        source.copy(
                            imports = source.imports.map { it.copy(targetModuleHash = ByteArray(32)) },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(artifact, ArtifactWriteLimits()).none { it.detail.contains("semantic hash") })
        assertTrue(validateArtifact(stale, ArtifactWriteLimits()).any { it.detail.contains("semantic hash") })
    }

    @Test
    fun `block fixed cost is bounded by the manifest before serialization`() {
        val artifact =
            executableArtifact(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
            )

        assertTrue(
            validateArtifact(
                artifact.copy(manifest = Manifest.minimal(maximumBlockCost = 7u)),
                ArtifactWriteLimits(),
            ).none { it.detail.contains("fixed cost") },
        )
        assertTrue(
            validateArtifact(
                artifact.copy(manifest = Manifest.minimal(maximumBlockCost = 6u)),
                ArtifactWriteLimits(),
            ).any { it.detail.contains("fixed cost") },
        )
    }

    @Test
    fun `entry and function block metadata match pinned CFG rules`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val module = artifact.modules[0]
        val badEntry = artifact.copy(entry = EntryPoint(ModuleId.of(0u), FunctionId.of(9u)))
        val zeroBlocks =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = module.functions.toMutableList().also { it[1] = it[1].copy(blockCount = 0u) },
                        ),
                        artifact.modules[1],
                    ),
            )
        val abstractWithBlocks =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions =
                                module.functions.toMutableList().also {
                                    it[1] = it[1].copy(flags = it[1].flags + FunctionFlag.ABSTRACT)
                                },
                        ),
                        artifact.modules[1],
                    ),
            )
        val overlapping =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions =
                                module.functions.toMutableList().also {
                                    it[1] = it[1].copy(firstBlock = BlockId.of(1u), blockCount = 2u)
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(badEntry, ArtifactWriteLimits()).any { it.detail.contains("entry function") })
        assertTrue(validateArtifact(zeroBlocks, ArtifactWriteLimits()).any { it.detail.contains("non-abstract function has no blocks") })
        assertTrue(validateArtifact(abstractWithBlocks, ArtifactWriteLimits()).any { it.detail.contains("abstract function") })
        assertTrue(validateArtifact(overlapping, ArtifactWriteLimits()).any { it.detail.contains("contiguous") })
    }

    @Test
    fun `valid executable instructions pass semantic validation`() {
        val cases =
            listOf(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Imported(ImportId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
                Instruction.CallSuspend(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                    BlockId.of(1u),
                ),
                Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)),
                Instruction.CapabilityCallAsync(
                    Destination.Unit,
                    CapabilityId.of(0u),
                    1u,
                    listOf(RegisterId.of(0u)),
                    BlockId.of(1u),
                ),
            )

        cases.forEach { instruction ->
            assertEquals(emptyList(), validateArtifact(executableArtifact(instruction), ArtifactWriteLimits()), instruction.toString())
        }
    }

    @Test
    fun `instruction registers are validated against the owning function table`() {
        val bad = RegisterId.of(9u)
        val cases =
            listOf(
                Instruction.Call(
                    Destination.Register(bad),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ) to
                    "destination register",
                Instruction.StringConcat(RegisterId.of(3u), bad, RegisterId.of(2u)) to "source register",
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(bad, RegisterId.of(1u)),
                ) to
                    "argument register",
            )

        cases.forEach { (instruction, expected) ->
            val errors = validateArtifact(executableArtifact(instruction), ArtifactWriteLimits())
            assertTrue(errors.any { it.code == ArtifactWriteErrorCode.BAD_REFERENCE && it.detail.contains(expected) }, errors.toString())
        }
    }

    @Test
    fun `direct calls validate local and imported function references`() {
        val badLocal =
            executableArtifact(
                Instruction.Call(Destination.Unit, FunctionRef.Local(FunctionId.of(9u)), emptyList()),
            )
        val badImport =
            executableArtifact(
                Instruction.Call(Destination.Unit, FunctionRef.Imported(ImportId.of(0u)), emptyList()),
            )

        assertTrue(validateArtifact(badLocal, ArtifactWriteLimits()).any { it.detail.contains("local function reference") })
        assertTrue(validateArtifact(badImport, ArtifactWriteLimits()).any { it.detail.contains("imported function reference") })
    }

    @Test
    fun `imported calls resolve the unique export matching the expected signature`() {
        val call =
            Instruction.Call(
                Destination.Register(RegisterId.of(0u)),
                FunctionRef.Imported(ImportId.of(1u)),
                listOf(RegisterId.of(0u), RegisterId.of(1u)),
            )
        val artifact = executableArtifact(call)
        val module = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            imports =
                                module.imports.toMutableList().also {
                                    it[1] = it[1].copy(expectedSignature = TypeRef.Local(TypeId.of(0u)))
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("imported function reference") })
    }

    @Test
    fun `direct calls validate signature arity argument types and result destination`() {
        val cases =
            listOf(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u)),
                ) to "arity",
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(1u), RegisterId.of(0u)),
                ) to "argument type",
                Instruction.Call(
                    Destination.Register(RegisterId.of(1u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ) to "result destination",
                Instruction.Call(
                    Destination.Unit,
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ) to "result destination",
            )

        cases.forEach { (instruction, expected) ->
            val errors = validateArtifact(executableArtifact(instruction), ArtifactWriteLimits())
            assertTrue(errors.any { it.code == ArtifactWriteErrorCode.INVALID_RANGE && it.detail.contains(expected) }, errors.toString())
        }
    }

    @Test
    fun `capability async validates descriptor operation and registers`() {
        val cases =
            listOf(
                Instruction.CapabilityCallAsync(Destination.Unit, CapabilityId.of(1u), 0u, emptyList(), BlockId.of(1u)) to
                    "capability id",
                Instruction.CapabilityCallAsync(Destination.Unit, CapabilityId.of(0u), 2u, emptyList(), BlockId.of(1u)) to
                    "operation",
                Instruction.CapabilityCallAsync(
                    Destination.Register(RegisterId.of(9u)),
                    CapabilityId.of(0u),
                    0u,
                    emptyList(),
                    BlockId.of(1u),
                ) to "destination register",
                Instruction.CapabilityCallAsync(
                    Destination.Unit,
                    CapabilityId.of(0u),
                    0u,
                    listOf(RegisterId.of(9u)),
                    BlockId.of(1u),
                ) to "argument register",
            )

        cases.forEach { (instruction, expected) ->
            val errors = validateArtifact(executableArtifact(instruction), ArtifactWriteLimits())
            assertTrue(errors.any { it.detail.contains(expected) }, errors.toString())
        }
    }

    @Test
    fun `string concat requires string operands and destination`() {
        val cases =
            listOf(
                Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(0u), RegisterId.of(2u)),
                Instruction.StringConcat(RegisterId.of(0u), RegisterId.of(1u), RegisterId.of(2u)),
            )

        cases.forEach { instruction ->
            val errors = validateArtifact(executableArtifact(instruction), ArtifactWriteLimits())
            assertTrue(
                errors.any { it.code == ArtifactWriteErrorCode.INVALID_RANGE && it.detail.contains("kotlin.String") },
                errors.toString(),
            )
        }
    }

    @Test
    fun `string concat requires the unique canonical standard library string export`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val invalid =
            artifact.copy(
                modules = listOf(artifact.modules[0], artifact.modules[1].copy(exports = emptyList())),
            )

        val errors = validateArtifact(invalid, ArtifactWriteLimits())

        assertTrue(errors.any { it.detail.contains("kotlin.String") }, errors.toString())
    }

    @Test
    fun `string concat obeys the pinned allocation block placement rule`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val module = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            blocks =
                                module.blocks.toMutableList().also {
                                    it[0] = it[0].copy(instructions = listOf(Instruction.Null(RegisterId.of(1u))) + it[0].instructions)
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("allocation") })
    }

    @Test
    fun `direct calls reject inconsistent target function metadata`() {
        val call =
            Instruction.Call(
                Destination.Register(RegisterId.of(0u)),
                FunctionRef.Local(FunctionId.of(1u)),
                listOf(RegisterId.of(0u), RegisterId.of(1u)),
            )
        val artifact = executableArtifact(call)
        val module = artifact.modules[0]
        val badParameterCount =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = module.functions.toMutableList().also { it[1] = it[1].copy(parameterCount = 1u) },
                        ),
                        artifact.modules[1],
                    ),
            )
        val signature = module.types[1] as NominalType.Function
        val badSuspendingFlag =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            types = module.types.toMutableList().also { it[1] = signature.copy(suspending = true) },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(badParameterCount, ArtifactWriteLimits()).any { it.detail.contains("target function metadata") })
        assertTrue(validateArtifact(badSuspendingFlag, ArtifactWriteLimits()).any { it.detail.contains("target function metadata") })
    }

    @Test
    fun `resume blocks stay in the owning function and suspending instructions terminate`() {
        val outside =
            Instruction.CallSuspend(
                Destination.Register(RegisterId.of(0u)),
                FunctionRef.Local(FunctionId.of(1u)),
                listOf(RegisterId.of(0u), RegisterId.of(1u)),
                BlockId.of(2u),
            )
        val followed =
            executableArtifact(
                Instruction.CallSuspend(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                    BlockId.of(1u),
                ),
                appendControlFlow = true,
            )

        assertTrue(validateArtifact(executableArtifact(outside), ArtifactWriteLimits()).any { it.detail.contains("resume block") })
        assertTrue(validateArtifact(followed, ArtifactWriteLimits()).any { it.detail.contains("terminator before") })
    }

    @Test
    fun `ordinary successor blocks stay in the owning function`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val module = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            blocks =
                                module.blocks.toMutableList().also {
                                    it[0] = it[0].copy(instructions = listOf(Instruction.Jump(BlockId.of(2u))))
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("successor block") })
    }

    @Test
    fun `block indices must belong to their declared owner function range`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val module = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            blocks = module.blocks.toMutableList().also { it[0] = it[0].copy(owner = FunctionId.of(1u)) },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("owner function range") })
    }

    @Test
    fun `suspending terminators require a suspending owning function`() {
        val artifact =
            executableArtifact(
                Instruction.CapabilityCallAsync(Destination.Unit, CapabilityId.of(0u), 0u, emptyList(), BlockId.of(1u)),
            )
        val module = artifact.modules[0]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions =
                                module.functions.toMutableList().also {
                                    it[0] = it[0].copy(flags = setOf(FunctionFlag.STATIC))
                                },
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("non-suspending function") })
    }

    @Test
    fun `new instruction reads require definite assignment`() {
        val cases =
            listOf(
                Instruction.Call(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                ),
                Instruction.CallSuspend(
                    Destination.Register(RegisterId.of(0u)),
                    FunctionRef.Local(FunctionId.of(1u)),
                    listOf(RegisterId.of(0u), RegisterId.of(1u)),
                    BlockId.of(1u),
                ),
                Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)),
                Instruction.CapabilityCallAsync(
                    Destination.Unit,
                    CapabilityId.of(0u),
                    0u,
                    listOf(RegisterId.of(0u)),
                    BlockId.of(1u),
                ),
            )

        cases.forEach { instruction ->
            val artifact = executableArtifact(instruction)
            val module = artifact.modules[0]
            val callerSignature = module.types[0] as NominalType.Function
            val invalid =
                artifact.copy(
                    modules =
                        listOf(
                            module.copy(
                                types =
                                    module.types.toMutableList().also {
                                        it[0] = callerSignature.copy(parameters = emptyList())
                                    },
                                functions =
                                    module.functions.toMutableList().also {
                                        it[0] = it[0].copy(parameterCount = 0u)
                                    },
                            ),
                            artifact.modules[1],
                        ),
                )

            assertTrue(
                validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("uninitialized register") },
                instruction.toString(),
            )
        }
    }

    @Test
    fun `definite assignment intersects predecessor states at a join`() {
        val artifact = executableArtifact(Instruction.StringConcat(RegisterId.of(3u), RegisterId.of(1u), RegisterId.of(2u)))
        val module = artifact.modules[0]
        val callerSignature = module.types[0] as NominalType.Function
        val caller = module.functions[0]
        val callee = module.functions[1]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            types =
                                module.types.toMutableList().also {
                                    it[0] = callerSignature.copy(parameters = listOf(ValueType.Bool))
                                },
                            constants =
                                listOf(
                                    ru.lazyhat.compukters.compiler.artifact.model.Constant
                                        .I32(1),
                                ),
                            functions =
                                listOf(
                                    caller.copy(
                                        registers = listOf(ValueType.Bool, ValueType.I32),
                                        parameterCount = 1u,
                                        blockCount = 4u,
                                    ),
                                    callee.copy(firstBlock = BlockId.of(4u)),
                                ),
                            blocks =
                                listOf(
                                    Block(
                                        FunctionId.of(0u),
                                        false,
                                        listOf(Instruction.Branch(RegisterId.of(0u), BlockId.of(1u), BlockId.of(2u))),
                                    ),
                                    Block(
                                        FunctionId.of(0u),
                                        false,
                                        listOf(
                                            Instruction.Const(
                                                RegisterId.of(1u),
                                                ru.lazyhat.compukters.compiler.artifact.model.ConstantId
                                                    .of(0u),
                                            ),
                                            Instruction.Jump(BlockId.of(3u)),
                                        ),
                                    ),
                                    Block(FunctionId.of(0u), false, listOf(Instruction.Jump(BlockId.of(3u)))),
                                    Block(
                                        FunctionId.of(0u),
                                        false,
                                        listOf(
                                            Instruction.ArrayStore(RegisterId.of(0u), RegisterId.of(1u), RegisterId.of(1u)),
                                            Instruction.Return(Destination.Unit),
                                        ),
                                    ),
                                    module.blocks[2],
                                ),
                        ),
                        artifact.modules[1],
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("uninitialized register") })
    }

    @Test
    fun `ordinary control flow cannot target an exception handler`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val protectedBlock = module.blocks[2]
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            blocks =
                                module.blocks.toMutableList().also {
                                    it[2] =
                                        protectedBlock.copy(
                                            instructions =
                                                protectedBlock.instructions.dropLast(1) + Instruction.Jump(BlockId.of(4u)),
                                        )
                                },
                        ),
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("exception handler") })
    }

    @Test
    fun `dataflow uses only the function declared exception slice`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = listOf(module.functions.single().copy(exceptionCount = 0u)),
                        ),
                    ),
            )

        assertTrue(validateArtifact(invalid, ArtifactWriteLimits()).any { it.detail.contains("unreachable") })
    }

    @Test
    fun `exception slices are validated for abstract functions`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val abstractFunction =
            module.functions.single().copy(
                flags = setOf(FunctionFlag.ABSTRACT),
                firstBlock = BlockId.of(module.blocks.size.toUInt()),
                blockCount = 0u,
                firstException = UInt.MAX_VALUE,
                exceptionCount = 1u,
            )
        val invalid =
            artifact.copy(
                modules = listOf(module.copy(functions = module.functions + abstractFunction)),
            )

        val errors = validateArtifact(invalid, ArtifactWriteLimits())

        assertTrue(errors.any { it.detail.contains("exception range") }, errors.toString())
    }

    @Test
    fun `exception entries require compatible non-null reference types`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val function = module.functions.single()
        val exception = module.exceptions.single()

        val nullableRegister =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions =
                                listOf(
                                    function.copy(
                                        registers =
                                            function.registers.toMutableList().also {
                                                it[exception.exceptionRegister.value.toInt()] =
                                                    ValueType.Ref(true, TypeRef.Local(TypeId.of(0u)))
                                            },
                                    ),
                                ),
                        ),
                    ),
            )
        val nonReferenceCatch =
            artifact.copy(
                modules = listOf(module.copy(exceptions = listOf(exception.copy(catchType = TypeRef.Local(TypeId.of(1u)))))),
            )
        val incompatibleCatch =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            types = module.types + NominalType.Class(name = StringId.of(0u), final = true),
                            exceptions = listOf(exception.copy(catchType = TypeRef.Local(TypeId.of(3u)))),
                        ),
                    ),
            )

        assertTrue(validateArtifact(nullableRegister, ArtifactWriteLimits()).any { it.detail.contains("non-null reference") })
        assertTrue(validateArtifact(nonReferenceCatch, ArtifactWriteLimits()).any { it.detail.contains("catch type") })
        assertTrue(validateArtifact(incompatibleCatch, ArtifactWriteLimits()).any { it.detail.contains("incompatible") })
    }

    @Test
    fun `exception protected ranges may nest but never cross`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val base = module.exceptions.single().copy(catchType = null)
        val outer = base.copy(firstProtectedBlock = BlockId.of(0u), protectedBlockCount = 3u)
        val nested = base.copy(firstProtectedBlock = BlockId.of(1u), protectedBlockCount = 1u)
        val crossing = base.copy(firstProtectedBlock = BlockId.of(2u), protectedBlockCount = 2u)

        fun withExceptions(entries: List<ExceptionEntry>) =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = listOf(module.functions.single().copy(exceptionCount = entries.size.toUInt())),
                            exceptions = entries,
                        ),
                    ),
            )

        assertTrue(validateArtifact(withExceptions(listOf(outer, nested)), ArtifactWriteLimits()).none { it.detail.contains("cross") })
        assertTrue(validateArtifact(withExceptions(listOf(outer, crossing)), ArtifactWriteLimits()).any { it.detail.contains("cross") })
    }

    @Test
    fun `writer reports oversized parameter count instead of throwing`() {
        val artifact = languageRuntimeArtifact()
        val module = artifact.modules.single()
        val invalid =
            artifact.copy(
                modules =
                    listOf(
                        module.copy(
                            functions = listOf(module.functions.single().copy(parameterCount = UInt.MAX_VALUE)),
                        ),
                    ),
            )

        val result = ArtifactWriter.write(invalid)

        val failure = assertIs<ArtifactWriteResult.Failure>(result)
        assertTrue(failure.errors.any { it.detail.contains("parameter count") })
    }
}

private fun executableArtifact(
    instruction: Instruction,
    appendControlFlow: Boolean = false,
): Artifact {
    val isSuspending = instruction is Instruction.CallSuspend || instruction is Instruction.CapabilityCallAsync
    val firstInstructions =
        when {
            appendControlFlow -> listOf(instruction, Instruction.Jump(BlockId.of(1u)))
            isSuspending -> listOf(instruction)
            else -> listOf(instruction, Instruction.Jump(BlockId.of(1u)))
        }
    val strings = listOf("app", "callee", "entry").map(MetadataText::of)
    val stringType = ValueType.Ref(nullable = false, TypeRef.Imported(ImportId.of(0u)))
    val calleeSuspending = instruction is Instruction.CallSuspend
    val artifact =
        Artifact(
            semanticFeatures = setOf(SemanticFeature.COROUTINES, SemanticFeature.CAPABILITIES, SemanticFeature.MODULE_IMPORTS),
            manifest = Manifest.minimal(maximumBlockCost = 16u),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
            modules =
                listOf(
                    Module(
                        name = StringId.of(0u),
                        kind = ModuleKind.APPLICATION,
                        strings = strings,
                        types =
                            listOf(
                                NominalType.Function(
                                    StringId.of(2u),
                                    true,
                                    ValueType.Unit,
                                    listOf(ValueType.I32, stringType, stringType, stringType),
                                ),
                                NominalType.Function(StringId.of(1u), calleeSuspending, ValueType.I32, listOf(ValueType.I32, stringType)),
                            ),
                        imports =
                            listOf(
                                Import(
                                    SymbolKind.TYPE,
                                    ModuleId.of(1u),
                                    StringId.of(1u),
                                    TypeRef.Imported(ImportId.of(0u)),
                                    ByteArray(32),
                                ),
                                Import(
                                    SymbolKind.FUNCTION,
                                    ModuleId.of(1u),
                                    StringId.of(0u),
                                    TypeRef.Local(TypeId.of(1u)),
                                    ByteArray(32),
                                ),
                            ),
                        functions =
                            listOf(
                                Function(
                                    null,
                                    StringId.of(2u),
                                    TypeRef.Local(TypeId.of(0u)),
                                    setOf(FunctionFlag.STATIC, FunctionFlag.SUSPENDING),
                                    listOf(ValueType.I32, stringType, stringType, stringType),
                                    4u,
                                    BlockId.of(0u),
                                    2u,
                                    0u,
                                    0u,
                                ),
                                Function(
                                    null,
                                    StringId.of(1u),
                                    TypeRef.Local(TypeId.of(1u)),
                                    setOfNotNull(FunctionFlag.STATIC, FunctionFlag.SUSPENDING.takeIf { calleeSuspending }),
                                    listOf(ValueType.I32, stringType),
                                    2u,
                                    BlockId.of(2u),
                                    1u,
                                    0u,
                                    0u,
                                ),
                            ),
                        blocks =
                            listOf(
                                Block(FunctionId.of(0u), false, firstInstructions),
                                Block(FunctionId.of(0u), false, listOf(Instruction.Return(Destination.Unit))),
                                Block(FunctionId.of(1u), false, listOf(Instruction.Return(Destination.Register(RegisterId.of(0u))))),
                            ),
                    ),
                    Module(
                        name = StringId.of(0u),
                        kind = ModuleKind.LIBRARY,
                        strings = listOf(MetadataText.of("callee"), MetadataText.of("kotlin.String")),
                        types =
                            listOf(
                                NominalType.Class(name = StringId.of(1u), final = true),
                                NominalType.Function(
                                    StringId.of(0u),
                                    false,
                                    ValueType.I32,
                                    listOf(ValueType.I32, ValueType.Ref(false, TypeRef.Local(TypeId.of(0u)))),
                                ),
                            ),
                        exports =
                            listOf(
                                Export(
                                    SymbolKind.TYPE,
                                    ExportVisibility.PUBLIC_LIBRARY,
                                    StringId.of(1u),
                                    0u,
                                    TypeRef.Local(TypeId.of(0u)),
                                ),
                                Export(
                                    SymbolKind.FUNCTION,
                                    ExportVisibility.PUBLIC_LIBRARY,
                                    StringId.of(0u),
                                    0u,
                                    TypeRef.Local(TypeId.of(1u)),
                                ),
                            ),
                        functions =
                            listOf(
                                Function(
                                    null,
                                    StringId.of(0u),
                                    TypeRef.Local(TypeId.of(1u)),
                                    setOf(FunctionFlag.STATIC),
                                    listOf(ValueType.I32, ValueType.Ref(false, TypeRef.Local(TypeId.of(0u)))),
                                    2u,
                                    BlockId.of(0u),
                                    1u,
                                    0u,
                                    0u,
                                ),
                            ),
                        blocks =
                            listOf(
                                Block(
                                    FunctionId.of(0u),
                                    false,
                                    listOf(Instruction.Return(Destination.Register(RegisterId.of(0u)))),
                                ),
                            ),
                    ),
                ),
            capabilities =
                listOf(
                    Capability(StringId.of(0u), StringId.of(1u), AbiVersion(1u, 0u), required = true, operationCount = 2u),
                ),
        )
    val targetHash = encodeModuleSections(artifact.modules[1], ArtifactWriteLimits()).semanticHash
    return artifact.copy(
        modules =
            listOf(
                artifact.modules[0].copy(
                    imports = artifact.modules[0].imports.map { it.copy(targetModuleHash = targetHash) },
                ),
                artifact.modules[1],
            ),
    )
}
