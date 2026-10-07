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

package ru.lazyhat.compukters.compiler.artifact.read

import ru.lazyhat.compukters.compiler.artifact.analysis.ReferenceLiveness
import ru.lazyhat.compukters.compiler.artifact.analysis.runtimeExceptionKinds
import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.ArrayStorage
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.DebugEntry
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.Field
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.OrderedScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalAtom
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalShape
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.RuntimeExceptionKind
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import ru.lazyhat.compukters.compiler.artifact.write.channelArtifact
import ru.lazyhat.compukters.compiler.artifact.write.languageRuntimeArtifact
import ru.lazyhat.compukters.compiler.artifact.write.scalarLanguageRuntimeArtifact
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ArtifactReaderTest {
    @Test
    fun `inline value layouts and component instructions round trip with strict ABI and shape checks`() {
        fun r(index: UInt) = RegisterId.of(index)
        val original =
            ru.lazyhat.compukters.compiler.artifact.write
                .minimalArtifact()
        val base = original.modules.single()
        val inline = ValueType.Inline(TypeRef.Local(TypeId.of(1u)))
        val layout = listOf(ValueType.I32, ValueType.F64, ValueType.Bool)
        val values =
            layout.map(FunctionValue::scalar) +
                FunctionValue(inline, PhysicalShape(listOf(PhysicalAtom.I32, PhysicalAtom.F64, PhysicalAtom.I32)))
        val operations =
            listOf(
                Instruction.Const(r(0u), ConstantId.of(0u)),
                Instruction.Const(r(1u), ConstantId.of(1u)),
                Instruction.Const(r(2u), ConstantId.of(2u)),
                Instruction.InlineConstruct(r(3u), listOf(r(0u), r(1u), r(2u))),
                Instruction.InlineComponent(r(0u), r(3u), 0u),
                Instruction.Return(Destination.Unit),
            )
        val module =
            base.copy(
                types = base.types + NominalType.InlineValue(StringId.of(1u), layout),
                constants = listOf(Constant.I32(42), Constant.F64(3.5.toBits().toULong()), Constant.Bool(true)),
                functions = base.functions.map { it.copy(values = values) },
                blocks = listOf(base.blocks.single().copy(instructions = operations)),
            )
        val source =
            original.copy(
                minimumRuntimeAbi = AbiVersion(1u, 15u),
                manifest = Manifest(0u, 40u, 1u, 1u, 0u, 0u, 20u, 20u, ByteArray(32), ByteArray(32)),
                modules = listOf(module),
            )
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source)).bytes
        System.getProperty("compukter.vm.executableArtifact")?.let { path ->
            java.io
                .File("$path.inline.cpkt")
                .apply { parentFile.mkdirs() }
                .writeBytes(encoded)
        }
        val decoded = ArtifactReader.read(encoded).modules.single()
        assertEquals(module.types, decoded.types)
        assertEquals(values, decoded.functions.single().values)
        assertEquals(operations, decoded.blocks.single().instructions)
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(source.copy(minimumRuntimeAbi = AbiVersion(1u, 14u))))
        val badShape =
            module.copy(
                functions =
                    module.functions.map {
                        it.copy(
                            values =
                                values.dropLast(1) + FunctionValue(inline, PhysicalShape(listOf(PhysicalAtom.I32))),
                        )
                    },
            )
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(source.copy(modules = listOf(badShape))))
        val badConstruct =
            module.copy(
                blocks =
                    listOf(
                        module.blocks.single().copy(
                            instructions =
                                operations.map {
                                    if (it is Instruction.InlineConstruct) it.copy(components = listOf(r(0u), r(0u), r(2u))) else it
                                },
                        ),
                    ),
            )
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(source.copy(modules = listOf(badConstruct))))
    }

    @Test
    fun `unsigned numeric forms and conversion signedness round trip with ABI gate`() {
        fun r(index: UInt) = RegisterId.of(index)
        val original = languageRuntimeArtifact()
        val module = original.modules.single()
        val operations =
            listOf(
                Instruction.Add(ScalarValueType.U32, r(5u), r(3u), r(7u)),
                Instruction.Subtract(ScalarValueType.U64, r(10u), r(8u), r(9u)),
                Instruction.Multiply(ScalarValueType.U32, r(5u), r(3u), r(7u)),
                Instruction.Equal(ScalarValueType.U64, r(2u), r(8u), r(9u)),
                Instruction.Less(OrderedScalarValueType.U32, r(2u), r(3u), r(7u)),
                Instruction.GreaterOrEqual(OrderedScalarValueType.U64, r(2u), r(8u), r(9u)),
                Instruction.Convert(r(8u), r(3u), unsignedSource = true),
                Instruction.Convert(r(8u), r(3u), unsignedDestination = true),
                Instruction.Convert(r(8u), r(3u), unsignedSource = true, unsignedDestination = true),
            )
        for (operation in operations) {
            val first = module.blocks.first()
            val changed =
                module.copy(
                    constants = module.constants + Constant.I64(0),
                    functions = module.functions.map { it.copy(values = it.values + List(3) { FunctionValue.scalar(ValueType.I64) }) },
                    blocks =
                        listOf(
                            first.copy(
                                instructions =
                                    first.instructions.dropLast(1) +
                                        listOf(
                                            Instruction.Const(r(8u), ConstantId.of(2u)),
                                            Instruction.Const(r(9u), ConstantId.of(2u)),
                                            operation,
                                        ) + first.instructions.last(),
                            ),
                        ) + module.blocks.drop(1),
                )
            val source =
                original.copy(
                    minimumRuntimeAbi = AbiVersion(1u, 14u),
                    manifest = Manifest.minimal(maximumBlockCost = 20u),
                    modules = listOf(changed),
                )
            val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source)).bytes
            assertEquals(
                operation,
                ArtifactReader
                    .read(bytes)
                    .modules
                    .single()
                    .blocks
                    .first()
                    .instructions
                    .dropLast(1)
                    .last(),
            )
            assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(source.copy(minimumRuntimeAbi = AbiVersion(1u, 13u))))
        }
    }

    @Test
    fun `explicit array storage round trips and rejects legacy ABI or mismatched scalar kinds`() {
        for (storage in ArrayStorage.entries.filter { it != ArrayStorage.NATURAL }) {
            val original = languageRuntimeArtifact()
            val array = NominalType.Array(StringId.of(0u), requireNotNull(storage.requiredElement), storage = storage)
            val module = original.modules.single()
            val source =
                original.copy(
                    minimumRuntimeAbi = AbiVersion(1u, 14u),
                    modules = listOf(module.copy(types = module.types + array)),
                )
            val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source)).bytes
            assertEquals(
                array,
                ArtifactReader
                    .read(bytes)
                    .modules
                    .single()
                    .types
                    .last(),
            )
            assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(source.copy(minimumRuntimeAbi = AbiVersion(1u, 13u))))
            assertIs<ArtifactWriteResult.Failure>(
                ArtifactWriter.write(
                    source.copy(
                        modules = listOf(module.copy(types = module.types + array.copy(element = ValueType.Bool))),
                    ),
                ),
            )
        }
    }

    @Test
    fun `integer division requires its factory role and rebuild ABI but floating division does not`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val first = module.blocks.first()
        val division = Instruction.Divide(RegisterId.of(7u), RegisterId.of(3u), RegisterId.of(7u))
        val blocks =
            listOf(first.copy(instructions = first.instructions.dropLast(1) + division + first.instructions.last())) + module.blocks.drop(1)
        val operationModule = module.copy(blocks = blocks)
        val missing =
            source.copy(
                minimumRuntimeAbi = AbiVersion(1u, 9u),
                manifest = Manifest.minimal(maximumBlockCost = 16u),
                modules = listOf(operationModule),
            )
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(missing))
        val role =
            NominalType.Class(
                name = StringId.of(0u),
                superType = TypeRef.Local(TypeId.of(0u)),
                runtimeExceptionKind = RuntimeExceptionKind.ARITHMETIC,
            )
        val valid = missing.copy(modules = listOf(operationModule.copy(types = operationModule.types + role)))
        val result = ArtifactWriter.write(valid)
        assertIs<ArtifactWriteResult.Success>(result, result.toString())
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(valid.copy(minimumRuntimeAbi = AbiVersion(1u, 8u))))
        assertEquals(
            emptySet(),
            Instruction
                .Divide(
                    ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType.F32,
                    RegisterId.of(0u),
                    RegisterId.of(1u),
                    RegisterId.of(2u),
                ).runtimeExceptionKinds(),
        )
    }

    @Test
    fun `runtime exception roles round trip and reject legacy duplicate and stateful classes`() {
        val source = languageRuntimeArtifact()
        val module =
            source.modules.single().let { original ->
                original.copy(types = original.types.map { if (it is NominalType.Class) it.copy(runtimeExceptionKind = null) else it })
            }
        val roles =
            RuntimeExceptionKind.entries.map { kind ->
                NominalType.Class(
                    name = StringId.of(0u),
                    superType = TypeRef.Local(TypeId.of(0u)),
                    runtimeExceptionKind = kind,
                )
            }
        val artifact = source.copy(minimumRuntimeAbi = AbiVersion(1u, 9u), modules = listOf(module.copy(types = module.types + roles)))
        val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
        assertEquals(
            roles,
            ArtifactReader
                .read(bytes)
                .modules
                .single()
                .types
                .takeLast(roles.size),
        )
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(minimumRuntimeAbi = AbiVersion(1u, 8u))))
        for (invalid in listOf(
            roles.first().copy(abstract = true),
            roles.first().copy(throwableRoot = true),
            roles.first().copy(genericArity = 1u),
            roles.first().copy(fieldCount = 1u),
            roles.first().copy(methodCount = 1u),
            roles.first().copy(initializer = FunctionId.of(0u)),
            roles.first().copy(superType = TypeRef.Local(TypeId.of(3u))),
            roles.first().copy(superType = TypeRef.Local(TypeId.of(module.types.size.toUInt()))),
            roles.first().copy(superType = null),
        )) {
            assertIs<ArtifactWriteResult.Failure>(
                ArtifactWriter.write(artifact.copy(modules = listOf(module.copy(types = module.types + invalid + roles.drop(1))))),
            )
        }
        assertIs<ArtifactWriteResult.Failure>(
            ArtifactWriter.write(artifact.copy(modules = listOf(module.copy(types = module.types + roles + roles.first())))),
        )
        val statefulParent = roles.first().copy(runtimeExceptionKind = null, fieldCount = 1u)
        val child = roles.first().copy(superType = TypeRef.Local(TypeId.of(module.types.size.toUInt())))
        assertIs<ArtifactWriteResult.Failure>(
            ArtifactWriter.write(
                artifact.copy(modules = listOf(module.copy(types = module.types + statefulParent + child + roles.drop(1)))),
            ),
        )
    }

    @Test
    fun `Throwable root role round trips only with its checked payload and runtime ABI`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val root = (module.types[0] as NominalType.Class).copy(throwableRoot = true, fieldCount = 2u)
        val rootRef = TypeRef.Local(TypeId.of(0u))
        val stringRef = TypeRef.Local(TypeId.of(3u))
        val fields =
            listOf(
                Field(rootRef, StringId.of(0u), ValueType.Ref(true, stringRef), mutable = false, static = false),
                Field(rootRef, StringId.of(2u), ValueType.Ref(true, rootRef), mutable = false, static = false),
            )
        val typedModule =
            module.copy(
                types = listOf(root) + module.types.drop(1),
                fields = fields,
            )
        val artifact = source.copy(minimumRuntimeAbi = AbiVersion(1u, 9u), modules = listOf(typedModule))
        val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
        assertEquals(
            root,
            ArtifactReader
                .read(bytes)
                .modules
                .single()
                .types[0],
        )
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(minimumRuntimeAbi = AbiVersion(1u, 7u))))
        for (invalid in listOf(
            root.copy(abstract = true),
            root.copy(final = true),
            root.copy(genericArity = 1u),
            root.copy(fieldCount = 1u),
            root.copy(fieldStart = UInt.MAX_VALUE),
            root.copy(methodCount = 1u),
            root.copy(initializer = FunctionId.of(0u)),
            root.copy(superType = rootRef),
        )) {
            assertIs<ArtifactWriteResult.Failure>(
                ArtifactWriter.write(
                    artifact.copy(
                        modules = listOf(typedModule.copy(types = listOf(invalid) + typedModule.types.drop(1))),
                    ),
                ),
            )
        }
        for (invalidFields in listOf(
            fields.toMutableList().also { it[0] = it[0].copy(type = ValueType.I32) },
            fields.toMutableList().also { it[0] = it[0].copy(type = ValueType.Ref(false, stringRef)) },
            fields.toMutableList().also { it[1] = it[1].copy(type = ValueType.Ref(true, stringRef)) },
            fields.toMutableList().also { it[1] = it[1].copy(static = true) },
        )) {
            assertIs<ArtifactWriteResult.Failure>(
                ArtifactWriter.write(artifact.copy(modules = listOf(typedModule.copy(fields = invalidFields)))),
            )
        }
        assertIs<ArtifactWriteResult.Failure>(
            ArtifactWriter.write(
                artifact.copy(
                    modules = listOf(typedModule.copy(types = typedModule.types + root)),
                ),
            ),
        )
    }

    @Test
    fun `array superclass round trips and requires ABI 1_7 and stateless root`() {
        val source = scalarLanguageRuntimeArtifact()
        val module = source.modules.single()
        val array = (module.types[1] as NominalType.Array).copy(superType = TypeRef.Local(TypeId.of(0u)))
        val artifact =
            source.copy(
                minimumRuntimeAbi = AbiVersion(1u, 9u),
                modules = listOf(module.copy(types = module.types.toMutableList().also { it[1] = array })),
            )
        val written = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact))
        assertEquals(
            array,
            ArtifactReader
                .read(written.bytes)
                .modules
                .single()
                .types[1],
        )
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(minimumRuntimeAbi = AbiVersion(1u, 6u))))
        val root = module.types[0] as NominalType.Class
        for (invalid in listOf(
            root.copy(abstract = true),
            root.copy(final = true),
            root.copy(genericArity = 1u),
            root.copy(superType = TypeRef.Local(TypeId.of(0u))),
            root.copy(interfaces = listOf(TypeRef.Local(TypeId.of(0u)))),
            root.copy(fieldCount = 1u),
            root.copy(methodCount = 1u),
            root.copy(initializer = FunctionId.of(0u)),
        )) {
            assertIs<ArtifactWriteResult.Failure>(
                ArtifactWriter.write(
                    artifact.copy(
                        modules =
                            listOf(
                                artifact.modules.single().copy(
                                    types =
                                        artifact.modules.single().types.toMutableList().also {
                                            it[0] =
                                                invalid
                                        },
                                ),
                            ),
                    ),
                ),
            )
        }
        assertIs<ArtifactWriteResult.Failure>(
            ArtifactWriter.write(
                artifact.copy(
                    modules =
                        listOf(
                            module.copy(
                                types =
                                    module.types.toMutableList().also {
                                        it[1] =
                                            array.copy(superType = TypeRef.Local(TypeId.of(2u)))
                                    },
                            ),
                        ),
                ),
            ),
        )
        val legacy = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source))
        assertEquals(
            null,
            (
                ArtifactReader
                    .read(legacy.bytes)
                    .modules
                    .single()
                    .types[1] as NominalType.Array
            ).superType,
        )
    }

    @Test
    fun `heterogeneous reference comparisons round trip with ABI gate and typed operands`() {
        val source = scalarLanguageRuntimeArtifact()
        val module = source.modules.single()
        for (comparison in listOf(
            Instruction.RefEqual(RegisterId.of(2u), RegisterId.of(4u), RegisterId.of(0u)),
            Instruction.RefNotEqual(RegisterId.of(2u), RegisterId.of(4u), RegisterId.of(0u)),
        )) {
            val blocks =
                module.blocks.mapIndexed { index, block ->
                    if (index ==
                        1
                    ) {
                        block.copy(instructions = block.instructions.dropLast(1) + comparison + block.instructions.last())
                    } else {
                        block
                    }
                }
            val artifact =
                source.copy(
                    minimumRuntimeAbi = AbiVersion(1u, 9u),
                    manifest = Manifest.minimal(maximumBlockCost = 20u),
                    modules = listOf(module.copy(blocks = blocks)),
                )
            val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
            assertContentEquals(bytes, assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(ArtifactReader.read(bytes))).bytes)
            assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(minimumRuntimeAbi = AbiVersion(1u, 5u))))
            val bad = Instruction.RefEqual(RegisterId.of(2u), RegisterId.of(4u), RegisterId.of(3u))
            val invalid = blocks.map { block -> block.copy(instructions = block.instructions.map { if (it == comparison) bad else it }) }
            assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(modules = listOf(module.copy(blocks = invalid)))))
            val compatible = Instruction.RefEqual(RegisterId.of(2u), RegisterId.of(0u), RegisterId.of(1u))
            val legacy =
                blocks.map { block ->
                    block.copy(
                        instructions =
                            block.instructions.map {
                                if (it ==
                                    comparison
                                ) {
                                    compatible
                                } else {
                                    it
                                }
                            },
                    )
                }
            assertIs<ArtifactWriteResult.Success>(
                ArtifactWriter.write(artifact.copy(minimumRuntimeAbi = AbiVersion(1u, 9u), modules = listOf(module.copy(blocks = legacy)))),
            )
        }
    }

    @Test
    fun `array copy round trips and requires valid operands feature and runtime ABI`() {
        val source = scalarLanguageRuntimeArtifact()
        val module = source.modules.single()
        val copy = Instruction.ArrayCopy(RegisterId.of(4u), RegisterId.of(4u), RegisterId.of(7u), RegisterId.of(7u), RegisterId.of(7u))
        val blocks =
            module.blocks.mapIndexed { index, block ->
                if (index == 1) block.copy(instructions = block.instructions.dropLast(1) + copy + block.instructions.last()) else block
            }
        val artifact =
            source.copy(
                minimumRuntimeAbi = AbiVersion(1u, 9u),
                semanticFeatures = source.semanticFeatures + SemanticFeature.ARRAY_COPY,
                manifest = Manifest.minimal(maximumBlockCost = 20u),
                modules = listOf(module.copy(blocks = blocks)),
            )
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
        assertContentEquals(encoded, assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(ArtifactReader.read(encoded))).bytes)
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(minimumRuntimeAbi = AbiVersion(1u, 4u))))
        assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(artifact.copy(semanticFeatures = source.semanticFeatures)))
        for (bad in listOf(copy.copy(source = RegisterId.of(3u)), copy.copy(length = RegisterId.of(4u)))) {
            val invalidBlocks = blocks.map { block -> block.copy(instructions = block.instructions.map { if (it == copy) bad else it }) }
            assertIs<ArtifactWriteResult.Failure>(
                ArtifactWriter.write(artifact.copy(modules = listOf(module.copy(blocks = invalidBlocks)))),
            )
        }
    }

    @Test
    fun `reader rejects malformed source position indices and coordinates`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val entry = DebugEntry(FunctionId.of(0u), BlockId.of(0u), 0u, 12u, 19u, null, MetadataText.of("src/main.kt"), 2u, 4u)
        val encoded =
            assertIs<ArtifactWriteResult.Success>(
                ArtifactWriter.write(source.copy(modules = listOf(module.copy(debug = listOf(entry))))),
            ).bytes
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        val directory = (0 until buffer.getInt(16)).map { 64 + it * 32 }.single { buffer.getShort(it).toInt() and 0xffff == 0x8001 }
        val payload = buffer.getLong(directory + 8).toInt()
        // One indexed record: 16-byte envelope, two offsets; then its 12-byte payload.
        val record = payload + 24
        for ((offset, value) in listOf(record to 1, record + 4 to 0, record + 8 to 0, directory + 4 to 0)) {
            val malformed = encoded.copyOf()
            ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            val digest = MessageDigest.getInstance("SHA-256").digest(malformed.copyOfRange(0, malformed.size - 32))
            digest.copyInto(malformed, malformed.size - 32)
            assertFailsWith<IllegalArgumentException> { ArtifactReader.read(malformed) }
        }
    }

    @Test
    fun `source positions round trip without changing semantic identity`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val entry = DebugEntry(FunctionId.of(0u), BlockId.of(0u), 0u, 12u, 19u, null, MetadataText.of("src/main.kt"), 2u, 4u)
        val mapped = module.copy(debug = listOf(entry))
        assertContentEquals(ArtifactWriter.moduleSemanticHash(module), ArtifactWriter.moduleSemanticHash(mapped))
        val bytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source.copy(modules = listOf(mapped)))).bytes
        assertEquals(
            listOf(entry),
            ArtifactReader
                .read(bytes)
                .modules
                .single()
                .debug,
        )
        assertContentEquals(bytes, assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(ArtifactReader.read(bytes))).bytes)
        val withoutPosition = mapped.copy(debug = listOf(entry.copy(sourceLine = null, sourceColumn = null)))
        val oldBytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source.copy(modules = listOf(withoutPosition)))).bytes
        assertEquals(
            withoutPosition.debug,
            ArtifactReader
                .read(oldBytes)
                .modules
                .single()
                .debug,
        )
        for (invalid in listOf(entry.copy(sourceLine = 0u), entry.copy(sourceColumn = null))) {
            assertIs<ArtifactWriteResult.Failure>(ArtifactWriter.write(source.copy(modules = listOf(mapped.copy(debug = listOf(invalid))))))
        }
    }

    @Test
    fun `round trip preserves physical shapes and exact roots`() {
        val source = languageRuntimeArtifact()
        val module = source.modules.single()
        val function = module.functions.single()
        val shaped =
            function.copy(
                values =
                    function.values.toMutableList().also {
                        it[0] = it[0].copy(physicalShape = PhysicalShape(listOf(PhysicalAtom.I32, PhysicalAtom.REF32)))
                    },
            )
        val shapedModule = module.copy(functions = listOf(shaped))
        val rooted = shaped.copy(safepointRoots = ReferenceLiveness.derive(shapedModule, shaped))
        val artifact = source.copy(modules = listOf(shapedModule.copy(functions = listOf(rooted))))

        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(artifact)).bytes
        val decoded = ArtifactReader.read(encoded)

        assertEquals(
            rooted.values,
            decoded.modules
                .single()
                .functions
                .single()
                .values,
        )
        assertEquals(
            rooted.safepointRoots,
            decoded.modules
                .single()
                .functions
                .single()
                .safepointRoots,
        )
    }

    @Test
    fun `writer reader writer round trip preserves canonical bytes`() {
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact())).bytes
        val decoded = ArtifactReader.read(encoded)
        val repeatedResult = ArtifactWriter.write(decoded)
        val repeated = assertIs<ArtifactWriteResult.Success>(repeatedResult, repeatedResult.toString()).bytes

        assertContentEquals(encoded, repeated)
    }

    @Test
    fun `channel instructions and manifest limits round trip canonically`() {
        val source = channelArtifact()
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(source)).bytes
        val decoded = ArtifactReader.read(encoded)

        assertEquals(1u, decoded.manifest.maximumChannels)
        assertEquals(1u, decoded.manifest.maximumChannelValues)
        assertEquals(source.modules.single().blocks, decoded.modules.single().blocks)
        val repeated = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(decoded)).bytes
        assertContentEquals(encoded, repeated)
    }

    @Test
    fun `reader rejects corruption and trailing bytes`() {
        val encoded = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(languageRuntimeArtifact())).bytes
        val corrupted = encoded.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(corrupted) }
        assertFailsWith<IllegalArgumentException> { ArtifactReader.read(encoded + 0) }
    }

    @Test
    fun `class initializer survives writer reader round trip`() {
        val source = scalarLanguageRuntimeArtifact()
        val module = source.modules.single()
        val initializerId = FunctionId.of(1u)
        val artifact =
            source.copy(
                modules =
                    listOf(
                        module.copy(
                            types =
                                module.types.toMutableList().also { types ->
                                    types[0] = (types[0] as NominalType.Class).copy(initializer = initializerId)
                                },
                            functions =
                                module.functions +
                                    module.functions.single().copy(
                                        owner = TypeRef.Local(TypeId.of(0u)),
                                        firstBlock = BlockId.of(module.blocks.size.toUInt()),
                                        blockCount = 1u,
                                        firstException = module.exceptions.size.toUInt(),
                                        exceptionCount = 0u,
                                    ),
                            blocks =
                                module.blocks +
                                    Block(initializerId, false, listOf(Instruction.Return(Destination.Unit))),
                        ),
                    ),
            )

        val writeResult = ArtifactWriter.write(artifact)
        val encoded = assertIs<ArtifactWriteResult.Success>(writeResult, writeResult.toString()).bytes
        val decoded = ArtifactReader.read(encoded)

        assertEquals(initializerId, (decoded.modules.single().types[0] as NominalType.Class).initializer)
    }
}
