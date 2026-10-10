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

package ru.lazyhat.compukters.compiler.artifact.link

import ru.lazyhat.compukters.compiler.artifact.model.AbiVersion
import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
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
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MethodReachabilityTest {
    @Test
    fun `final linking removes unused methods and rebuilds sparse and empty ranges`() {
        val linked = link(fixture(called = 2))
        assertEquals(listOf("main", "unused"), names(linked))
        val types =
            linked.modules
                .single()
                .types
                .filterIsInstance<NominalType.Class>()
        assertEquals(listOf(1u, 0u), types.map { it.methodCount })
        assertEquals(1u, types.first().methodStart)
        assertEquals(
            2,
            linked.modules
                .single()
                .blocks.size,
        )
    }

    @Test
    fun `retained virtual declaration keeps implementations on previously reached types`() {
        val linked = link(fixture(called = 1, virtual = true))
        assertEquals(listOf("main", "used", "used"), names(linked))
        assertEquals(
            listOf(1u, 1u),
            linked.modules
                .single()
                .types
                .filterIsInstance<NominalType.Class>()
                .map { it.methodCount },
        )
    }

    @Test
    fun `retained interface default keeps matching implementations and drops unrelated methods`() {
        val input = fixture(called = 1, virtual = true)
        val module = input.modules.single()
        val original = module.types[1] as NominalType.Class
        val types =
            module.types.toMutableList().apply {
                this[1] = NominalType.Interface(original.name, methodStart = original.methodStart, methodCount = original.methodCount)
                this[2] = (this[2] as NominalType.Class).copy(interfaces = listOf(type(1)))
            }
        val linked =
            link(
                input.copy(
                    modules =
                        listOf(
                            module.copy(
                                types = types,
                                functions =
                                    module.functions.map {
                                        it.copy(
                                            flags =
                                                it.flags - FunctionFlag.VIRTUAL,
                                        )
                                    },
                            ),
                        ),
                ),
            )
        assertEquals(listOf("main", "used", "used"), names(linked))
        assertEquals(
            1u,
            linked.modules
                .single()
                .types
                .filterIsInstance<NominalType.Interface>()
                .single()
                .methodCount,
        )
    }

    @Test
    fun `matching methods on unreachable types do not retain those types`() {
        val input = fixture(called = 1, virtual = true)
        val module = input.modules.single()
        val linked =
            link(
                input.copy(
                    modules =
                        listOf(
                            module.copy(
                                functions =
                                    module.functions.mapIndexed { i, f ->
                                        if (i ==
                                            0
                                        ) {
                                            f.copy(
                                                values = listOf(FunctionValue.scalar(ValueType.Ref(nullable = true, type = type(1)))),
                                            )
                                        } else {
                                            f
                                        }
                                    },
                            ),
                        ),
                ),
            )
        assertEquals(listOf("main", "used"), names(linked))
        assertEquals(
            1,
            linked.modules
                .single()
                .types
                .filterIsInstance<NominalType.Class>()
                .size,
        )
    }

    @Test
    fun `directly reached nonvirtual method does not retain same named unused implementations`() {
        val linked = link(fixture(called = 1))
        assertEquals(listOf("main", "used"), names(linked))
    }

    @Test
    fun `dispatch closure applies to types first reached inside retained method bodies`() {
        val input = fixture(called = 1, virtual = true)
        val module = input.modules.single()
        val functions =
            module.functions.mapIndexed { index, function ->
                when (index) {
                    0 -> function.copy(values = function.values.take(1))
                    1 -> function.copy(values = function.values + FunctionValue.scalar(ValueType.Ref(nullable = false, type = type(2))))
                    else -> function
                }
            }
        val blocks =
            module.blocks.mapIndexed { index, block ->
                if (index ==
                    1
                ) {
                    block.copy(instructions = listOf(Instruction.NewObject(RegisterId.of(1u), type(2))) + block.instructions)
                } else {
                    block
                }
            }
        val linked = link(input.copy(modules = listOf(module.copy(functions = functions, blocks = blocks))))
        assertEquals(listOf("main", "used", "used"), names(linked))
    }

    @Test
    fun `dispatch closure excludes same named methods with different parameter counts`() {
        val input = fixture(called = 1, virtual = true)
        val module = input.modules.single()
        val types =
            module.types.mapIndexed { index, type ->
                if (index == 4) (type as NominalType.Function).copy(parameters = type.parameters + ValueType.I32) else type
            }
        val functions =
            module.functions.mapIndexed { index, function ->
                if (index >=
                    3
                ) {
                    function.copy(parameterCount = 2u, values = function.values + FunctionValue.scalar(ValueType.I32))
                } else {
                    function
                }
            }
        val linked = link(input.copy(modules = listOf(module.copy(types = types, functions = functions))))
        assertEquals(listOf("main", "used"), names(linked))
    }

    @Test
    fun `ABI inference accounts for retained array superclass methods after pruning`() {
        val input = fixture(called = 1)
        val module = input.modules.single()
        val array = NominalType.Array(StringId.of(1u), ValueType.I32, superType = type(1))
        val functions =
            module.functions.mapIndexed { index, function ->
                if (index ==
                    0
                ) {
                    function.copy(values = function.values + FunctionValue.scalar(ValueType.Ref(nullable = true, type = type(5))))
                } else {
                    function
                }
            }
        val linked =
            LibraryModuleLinker.link(
                input.copy(modules = listOf(module.copy(types = module.types + array, functions = functions))),
                emptyList(),
                inferMinimumRuntimeAbi = true,
            )
        assertEquals(AbiVersion(1u, 10u), linked.minimumRuntimeAbi)
        assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(linked))
    }

    @Test
    fun `library assembly retains exported class method surface`() {
        val input = fixture(called = null)
        val app = input.modules.single()
        val library =
            app.copy(
                kind = ModuleKind.LIBRARY,
                exports =
                    listOf(
                        Export(SymbolKind.TYPE, ExportVisibility.PUBLIC_LIBRARY, StringId.of(0u), 1u, type(1)),
                    ),
            )
        val anchor =
            app.copy(
                types = listOf(app.types[0]),
                functions = listOf(app.functions[0].copy(values = emptyList())),
                blocks = listOf(app.blocks[0]),
            )
        val linked = LibraryModuleLinker.link(input.copy(modules = listOf(anchor, library)), emptyList(), preserveLibraryExports = true)
        assertEquals(
            2u,
            linked.modules[1]
                .types
                .filterIsInstance<NominalType.Class>()
                .first()
                .methodCount,
        )
        assertEquals(listOf("used", "unused"), names(linked, 1))
        assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(linked))
    }

    private fun link(input: Artifact): Artifact =
        LibraryModuleLinker.link(input, emptyList()).also {
            assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(it))
        }

    private fun names(
        artifact: Artifact,
        module: Int = 0,
    ): List<String> =
        artifact.modules[module].let { m ->
            m.functions.map { m.strings[it.name.value.toInt()].toString() }
        }

    private fun type(index: Int): TypeRef = TypeRef.Local(TypeId.of(index.toUInt()))

    private fun fixture(
        called: Int?,
        virtual: Boolean = false,
    ): Artifact {
        val strings = listOf("First", "Second", "app", "main", "unused", "used").map(MetadataText::of)
        val types =
            listOf(
                NominalType.Function(StringId.of(3u), false, ValueType.Unit, emptyList()),
                NominalType.Class(StringId.of(0u), methodStart = 1u, methodCount = 2u),
                NominalType.Class(StringId.of(1u), methodStart = 3u, methodCount = 2u),
                NominalType.Function(StringId.of(5u), false, ValueType.Unit, listOf(ValueType.Ref(nullable = true, type = type(1)))),
                NominalType.Function(StringId.of(5u), false, ValueType.Unit, listOf(ValueType.Ref(nullable = true, type = type(2)))),
            )
        val functions =
            (0..4).map { index ->
                val owner = if (index in 1..2) 1 else 2
                Function(
                    owner = if (index == 0) null else type(owner),
                    name =
                        StringId.of(
                            if (index == 0) {
                                3u
                            } else if (index % 2 == 1) {
                                5u
                            } else {
                                4u
                            },
                        ),
                    signature =
                        type(
                            if (index == 0) {
                                0
                            } else if (owner == 1) {
                                3
                            } else {
                                4
                            },
                        ),
                    flags =
                        if (index == 0) {
                            setOf(FunctionFlag.STATIC)
                        } else if (virtual) {
                            setOf(FunctionFlag.VIRTUAL)
                        } else {
                            emptySet()
                        },
                    values =
                        if (index ==
                            0
                        ) {
                            listOf(1, 2).map { FunctionValue.scalar(ValueType.Ref(nullable = true, type = type(it))) }
                        } else {
                            listOf(FunctionValue.scalar(ValueType.Ref(nullable = true, type = type(owner))))
                        },
                    parameterCount = if (index == 0) 0u else 1u,
                    firstBlock = BlockId.of(index.toUInt()),
                    blockCount = 1u,
                    firstException = 0u,
                    exceptionCount = 0u,
                )
            }
        val blocks =
            (0..4).map { index ->
                Block(
                    FunctionId.of(index.toUInt()),
                    false,
                    (
                        if (index == 0 &&
                            called != null
                        ) {
                            listOf(
                                Instruction.Call(
                                    Destination.Unit,
                                    FunctionRef.Local(FunctionId.of(called.toUInt())),
                                    listOf(RegisterId.of(0u)),
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    ).let { calls ->
                        if (calls.isEmpty()) {
                            calls
                        } else {
                            listOf(Instruction.Null(RegisterId.of(0u))) +
                                calls
                        }
                    } +
                        Instruction.Return(Destination.Unit),
                )
            }
        return Artifact(
            manifest = Manifest.minimal(maximumBlockCost = 64u, minimumSliceCost = 64u),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
            modules =
                listOf(
                    Module(
                        StringId.of(2u),
                        ModuleKind.APPLICATION,
                        strings = strings,
                        types = types,
                        functions = functions,
                        blocks = blocks,
                    ),
                ),
        )
    }
}
