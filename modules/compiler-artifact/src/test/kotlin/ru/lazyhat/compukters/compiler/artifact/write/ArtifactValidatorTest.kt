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
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
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
    return Artifact(
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
                            NominalType.Function(StringId.of(2u), true, ValueType.Unit, emptyList()),
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
                                0u,
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
}
