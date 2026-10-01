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

package ru.lazyhat.compukters.compiler.artifact.write

import ru.lazyhat.compukters.compiler.artifact.analysis.ReferenceLiveness
import ru.lazyhat.compukters.compiler.artifact.analysis.runtimeExceptionKinds
import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.ExceptionEntry
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType

internal fun minimalArtifact(instructions: List<Instruction> = listOf(Instruction.Return(Destination.Unit))): Artifact =
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
                                values = emptyList(),
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
                                instructions = instructions,
                            ),
                        ),
                ),
            ),
    )

/** Fixture production mirrors implicit linking dependencies; admission never repairs an artifact. */
internal fun Artifact.withRuntimeExceptionDependencies(): Artifact {
    val required =
        modules
            .flatMap { it.blocks }
            .flatMap { it.instructions }
            .flatMap { it.runtimeExceptionKinds() }
            .toSet()
    if (required.isEmpty()) return this
    val updated = modules.toMutableList()
    var owner = updated.indexOfFirst { module -> module.types.any { it is NominalType.Class && it.throwableRoot } }
    if (owner < 0) {
        owner =
            updated.indexOfFirst { module ->
                module.types.any { it is NominalType.Class && module.strings[it.name.value.toInt()].toString() == "kotlin.String" }
            }
        if (owner < 0) {
            owner = updated.size
            updated +=
                ru.lazyhat.compukters.compiler.artifact.model.Module(
                    name = StringId.of(1u),
                    kind = ModuleKind.LIBRARY,
                    strings = listOf("kotlin.String", "test.runtime").map(MetadataText::of),
                    types = listOf(NominalType.Class(name = StringId.of(0u), final = true)),
                )
        }
        val module = updated[owner]
        val root = TypeRef.Local(TypeId.of(module.types.size.toUInt()))
        val string =
            TypeRef.Local(
                TypeId.of(
                    module.types
                        .indexOfFirst {
                            it is NominalType.Class && module.strings[it.name.value.toInt()].toString() == "kotlin.String"
                        }.toUInt(),
                ),
            )
        updated[owner] =
            module.copy(
                types =
                    module.types +
                        NominalType.Class(
                            name = module.name,
                            throwableRoot = true,
                            fieldStart = module.fields.size.toUInt(),
                            fieldCount = 2u,
                        ),
                fields =
                    module.fields +
                        listOf(
                            ru.lazyhat.compukters.compiler.artifact.model
                                .Field(root, StringId.of(0u), ValueType.Ref(true, string), false, false),
                            ru.lazyhat.compukters.compiler.artifact.model
                                .Field(root, module.name, ValueType.Ref(true, root), false, false),
                        ),
            )
    }
    val present =
        updated
            .flatMap { it.types }
            .filterIsInstance<NominalType.Class>()
            .mapNotNull { it.runtimeExceptionKind }
            .toSet()
    val module = updated[owner]
    val root = TypeRef.Local(TypeId.of(module.types.indexOfFirst { it is NominalType.Class && it.throwableRoot }.toUInt()))
    updated[owner] =
        module.copy(
            types =
                module.types +
                    (required - present).sortedBy { it.artifactTag }.map {
                        NominalType.Class(name = module.name, superType = root, runtimeExceptionKind = it)
                    },
        )
    val hashes = updated.map { ArtifactWriter.moduleSemanticHash(it) }
    return copy(
        minimumRuntimeAbi = AbiVersion(1u, 9u),
        modules =
            updated.map {
                it.copy(
                    imports =
                        it.imports.map { import ->
                            import.copy(targetModuleHash = hashes[import.targetModule.value.toInt()])
                        },
                )
            },
    )
}

internal fun channelArtifact(): Artifact {
    val source = minimalArtifact()
    val module = source.modules.single()
    return ReferenceLiveness.derive(
        source.copy(
            minimumRuntimeAbi = AbiVersion(1u, 9u),
            semanticFeatures = setOf(SemanticFeature.COROUTINES, SemanticFeature.CHANNELS),
            manifest =
                Manifest(
                    requiredHeapBytes = 0u,
                    requiredStackBytes = 0u,
                    maximumCoroutines = 1u,
                    maximumCallDepth = 1u,
                    maximumHostRequests = 0u,
                    maximumEvents = 0u,
                    maximumBlockCost = 8u,
                    minimumSliceCost = 8u,
                    compilerAbi = ByteArray(32),
                    platformAbi = ByteArray(32),
                    maximumChannels = 1u,
                    maximumChannelValues = 1u,
                ),
            modules =
                listOf(
                    module.copy(
                        strings = module.strings + MetadataText.of("kotlin.String"),
                        types =
                            listOf(
                                (module.types.single() as NominalType.Function).copy(suspending = true),
                                NominalType.Class(name = StringId.of(0u), throwableRoot = true, fieldCount = 2u),
                                NominalType.Class(name = StringId.of(2u), final = true),
                                NominalType.Class(
                                    name = StringId.of(1u),
                                    superType = TypeRef.Local(TypeId.of(1u)),
                                    runtimeExceptionKind = RuntimeExceptionKind.ILLEGAL_ARGUMENT,
                                ),
                            ),
                        fields =
                            listOf(
                                ru.lazyhat.compukters.compiler.artifact.model.Field(
                                    TypeRef.Local(TypeId.of(1u)),
                                    StringId.of(0u),
                                    ValueType.Ref(true, TypeRef.Local(TypeId.of(2u))),
                                    false,
                                    false,
                                ),
                                ru.lazyhat.compukters.compiler.artifact.model.Field(
                                    TypeRef.Local(TypeId.of(1u)),
                                    StringId.of(1u),
                                    ValueType.Ref(true, TypeRef.Local(TypeId.of(1u))),
                                    false,
                                    false,
                                ),
                            ),
                        constants = listOf(Constant.I32(1)),
                        functions =
                            listOf(
                                module.functions.single().copy(
                                    flags = setOf(FunctionFlag.STATIC, FunctionFlag.SUSPENDING),
                                    values = List(3) { FunctionValue.scalar(ValueType.I32) },
                                    blockCount = 3u,
                                ),
                            ),
                        blocks =
                            listOf(
                                Block(
                                    FunctionId.of(0u),
                                    false,
                                    listOf(
                                        Instruction.Const(RegisterId.of(0u), ConstantId.of(0u)),
                                        Instruction.ChannelCreate(RegisterId.of(1u), RegisterId.of(0u)),
                                        Instruction.ChannelSend(RegisterId.of(1u), RegisterId.of(0u), BlockId.of(1u)),
                                    ),
                                ),
                                Block(
                                    FunctionId.of(0u),
                                    false,
                                    listOf(Instruction.ChannelReceive(RegisterId.of(2u), RegisterId.of(1u), BlockId.of(2u))),
                                ),
                                Block(FunctionId.of(0u), false, listOf(Instruction.Return(Destination.Unit))),
                            ),
                    ),
                ),
        ),
    )
}

internal fun scalarLanguageRuntimeArtifact(): Artifact {
    val source = languageRuntimeArtifact()
    val module = source.modules.single()
    return source.copy(
        minimumRuntimeAbi =
            ru.lazyhat.compukters.compiler.artifact.model
                .AbiVersion(1u, 9u),
        semanticFeatures = emptySet(),
        modules =
            listOf(
                module.copy(
                    types =
                        module.types.toMutableList().also {
                            it[0] =
                                (it[0] as NominalType.Class).copy(throwableRoot = false, fieldCount = 0u)
                            for (index in 4..6) {
                                it[index] = (it[index] as NominalType.Class).copy(superType = TypeRef.Local(TypeId.of(7u)))
                            }
                            it += NominalType.Class(name = StringId.of(0u), throwableRoot = true, fieldCount = 2u)
                        },
                    fields =
                        module.fields.map { field ->
                            field.copy(
                                owner = TypeRef.Local(TypeId.of(7u)),
                                type =
                                    if (field.type == ValueType.Ref(true, TypeRef.Local(TypeId.of(0u)))) {
                                        ValueType.Ref(true, TypeRef.Local(TypeId.of(7u)))
                                    } else {
                                        field.type
                                    },
                            )
                        },
                    exceptions = emptyList(),
                    functions = listOf(module.functions.single().copy(exceptionCount = 0u)),
                    blocks =
                        module.blocks.map { block ->
                            block.copy(
                                instructions =
                                    block.instructions.map {
                                        if (it is Instruction.Throw) {
                                            Instruction.Jump(
                                                BlockId.of(4u),
                                            )
                                        } else {
                                            it
                                        }
                                    },
                            )
                        },
                ),
            ),
    )
}

internal fun languageRuntimeArtifact(): Artifact =
    Artifact(
        minimumRuntimeAbi =
            ru.lazyhat.compukters.compiler.artifact.model
                .AbiVersion(1u, 9u),
        semanticFeatures = setOf(SemanticFeature.EXCEPTIONS),
        manifest = Manifest.minimal(maximumBlockCost = 10u),
        entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
        modules =
            listOf(
                Module(
                    name = StringId.of(1u),
                    kind = ModuleKind.APPLICATION,
                    strings =
                        listOf(
                            MetadataText.of("Box"),
                            MetadataText.of("app"),
                            MetadataText.of("array"),
                            MetadataText.of("entry"),
                            MetadataText.of("kotlin.String"),
                        ),
                    types =
                        listOf(
                            NominalType.Class(name = StringId.of(0u), throwableRoot = true, fieldCount = 2u),
                            NominalType.Array(name = StringId.of(2u), element = ValueType.I32),
                            NominalType.Function(
                                name = StringId.of(3u),
                                suspending = false,
                                result = ValueType.Unit,
                                parameters = emptyList(),
                            ),
                            NominalType.Class(name = StringId.of(4u), final = true),
                            NominalType.Class(
                                name = StringId.of(0u),
                                superType = TypeRef.Local(TypeId.of(0u)),
                                runtimeExceptionKind = RuntimeExceptionKind.INDEX_OUT_OF_BOUNDS,
                            ),
                            NominalType.Class(
                                name = StringId.of(0u),
                                superType = TypeRef.Local(TypeId.of(0u)),
                                runtimeExceptionKind = RuntimeExceptionKind.NEGATIVE_ARRAY_SIZE,
                            ),
                            NominalType.Class(
                                name = StringId.of(0u),
                                superType = TypeRef.Local(TypeId.of(0u)),
                                runtimeExceptionKind = RuntimeExceptionKind.NULL_POINTER,
                            ),
                        ),
                    constants = listOf(Constant.I32(0), Constant.I32(1)),
                    fields =
                        listOf(
                            ru.lazyhat.compukters.compiler.artifact.model.Field(
                                TypeRef.Local(TypeId.of(0u)),
                                StringId.of(0u),
                                ValueType.Ref(true, TypeRef.Local(TypeId.of(3u))),
                                false,
                                false,
                            ),
                            ru.lazyhat.compukters.compiler.artifact.model.Field(
                                TypeRef.Local(TypeId.of(0u)),
                                StringId.of(2u),
                                ValueType.Ref(true, TypeRef.Local(TypeId.of(0u))),
                                false,
                                false,
                            ),
                        ),
                    functions =
                        listOf(
                            Function(
                                owner = null,
                                name = StringId.of(3u),
                                signature = TypeRef.Local(TypeId.of(2u)),
                                flags = setOf(FunctionFlag.STATIC),
                                values =
                                    listOf(
                                        ValueType.Ref(false, TypeRef.Local(TypeId.of(0u))),
                                        ValueType.Ref(true, TypeRef.Local(TypeId.of(0u))),
                                        ValueType.Bool,
                                        ValueType.I32,
                                        ValueType.Ref(false, TypeRef.Local(TypeId.of(1u))),
                                        ValueType.I32,
                                        ValueType.Ref(false, TypeRef.Local(TypeId.of(0u))),
                                        ValueType.I32,
                                    ).map(FunctionValue::scalar),
                                parameterCount = 0u,
                                firstBlock = BlockId.of(0u),
                                blockCount = 5u,
                                firstException = 0u,
                                exceptionCount = 1u,
                            ),
                        ),
                    blocks =
                        listOf(
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = false,
                                instructions =
                                    listOf(
                                        Instruction.NewObject(RegisterId.of(0u), TypeRef.Local(TypeId.of(0u))),
                                        Instruction.Null(RegisterId.of(1u)),
                                        Instruction.Const(RegisterId.of(3u), ConstantId.of(1u)),
                                        Instruction.Const(RegisterId.of(7u), ConstantId.of(0u)),
                                        Instruction.IsType(RegisterId.of(2u), RegisterId.of(1u), TypeRef.Local(TypeId.of(0u))),
                                        Instruction.Branch(RegisterId.of(2u), BlockId.of(1u), BlockId.of(2u)),
                                    ),
                            ),
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = false,
                                instructions =
                                    listOf(
                                        Instruction.NewArray(RegisterId.of(4u), TypeRef.Local(TypeId.of(1u)), RegisterId.of(3u)),
                                        Instruction.ArrayStore(RegisterId.of(4u), RegisterId.of(7u), RegisterId.of(3u)),
                                        Instruction.ArrayLoad(RegisterId.of(5u), RegisterId.of(4u), RegisterId.of(7u)),
                                        Instruction.Jump(BlockId.of(3u)),
                                    ),
                            ),
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = false,
                                instructions =
                                    listOf(
                                        Instruction.NewObject(RegisterId.of(6u), TypeRef.Local(TypeId.of(0u))),
                                        Instruction.Throw(RegisterId.of(6u)),
                                    ),
                            ),
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = true,
                                instructions = listOf(Instruction.Jump(BlockId.of(3u))),
                            ),
                            Block(
                                owner = FunctionId.of(0u),
                                loopHeaderSafepoint = false,
                                instructions = listOf(Instruction.Return(Destination.Unit)),
                            ),
                        ),
                    exceptions =
                        listOf(
                            ExceptionEntry(
                                owner = FunctionId.of(0u),
                                firstProtectedBlock = BlockId.of(2u),
                                protectedBlockCount = 1u,
                                catchType = TypeRef.Local(TypeId.of(0u)),
                                handlerBlock = BlockId.of(4u),
                                exceptionRegister = RegisterId.of(6u),
                            ),
                        ),
                ),
            ),
    )
