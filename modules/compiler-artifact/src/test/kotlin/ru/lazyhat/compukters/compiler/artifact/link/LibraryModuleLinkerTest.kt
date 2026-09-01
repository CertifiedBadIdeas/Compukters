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
import ru.lazyhat.compukters.compiler.artifact.model.Capability
import ru.lazyhat.compukters.compiler.artifact.model.CapabilityId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
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
import ru.lazyhat.compukters.compiler.artifact.model.SemanticFeature
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriteResult
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryModuleLinkerTest {
    @Test
    fun `links the recursive reachable function and removes dead library records`() {
        val library = libraryModule()
        val unused = library.copy(strings = listOf("dead", "live", "unused-library").map(MetadataText::of))
        val linked =
            LibraryModuleLinker.link(
                application(library),
                mapOf("sample:library" to library, "sample:unused" to unused),
            )

        assertEquals(2, linked.modules.size)
        assertEquals(listOf("app", "sample-library"), linked.modules.map { module -> module.strings[module.name.value.toInt()].toString() })
        assertEquals(1, linked.modules[1].functions.size)
        assertEquals(1, linked.modules[1].blocks.size)
        assertEquals(listOf(Constant.I32(7)), linked.modules[1].constants)
        assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(linked))
    }

    @Test
    fun `two level libraries link identically for every input map order`() {
        val dependency = libraryModule()
        val facade = facadeModule(ArtifactWriter.moduleSemanticHash(dependency))
        val application = application(facade)

        val forward = LibraryModuleLinker.link(application, linkedMapOf("facade" to facade, "dependency" to dependency))
        val reverse = LibraryModuleLinker.link(application, linkedMapOf("dependency" to dependency, "facade" to facade))
        val forwardBytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(forward)).bytes
        val reverseBytes = assertIs<ArtifactWriteResult.Success>(ArtifactWriter.write(reverse)).bytes

        assertContentEquals(forwardBytes, reverseBytes)
        assertEquals(listOf("app", "alpha-library", "sample-library"), forward.modules.map(::moduleName))
    }

    @Test
    fun `only capabilities reached through retained functions survive`() {
        val library =
            libraryModule().let { source ->
                source.copy(
                    blocks =
                        listOf(
                            source.blocks[0].copy(
                                instructions =
                                    listOf(
                                        Instruction.CapabilityCallSync(Destination.Unit, CapabilityId.of(1u), 0u, emptyList()),
                                    ) + source.blocks[0].instructions,
                            ),
                            source.blocks[1].copy(
                                instructions =
                                    listOf(
                                        Instruction.CapabilityCallSync(Destination.Unit, CapabilityId.of(0u), 0u, emptyList()),
                                    ) + source.blocks[1].instructions,
                            ),
                        ),
                )
            }
        val base = application(library)
        val app =
            base.modules.single().copy(
                strings = base.modules.single().strings + listOf("z-unused", "z-used", "zz").map(MetadataText::of),
            )
        val input =
            base.copy(
                semanticFeatures = base.semanticFeatures + SemanticFeature.CAPABILITIES,
                modules = listOf(app),
                capabilities =
                    listOf(
                        Capability(StringId.of(5u), StringId.of(3u), AbiVersion(1u, 0u), true, 1u),
                        Capability(StringId.of(5u), StringId.of(4u), AbiVersion(1u, 0u), true, 1u),
                    ),
            )

        val linked = LibraryModuleLinker.link(input, mapOf("sample" to library))

        assertEquals(1, linked.capabilities.size)
        val linkedApp = linked.modules.first()
        assertEquals(
            "z-used",
            linkedApp.strings[
                linked.capabilities
                    .single()
                    .name.value
                    .toInt(),
            ].toString(),
        )
        assertTrue(SemanticFeature.CAPABILITIES in linked.semanticFeatures)
    }

    @Test
    fun `signature mismatch fails closed`() {
        val library = libraryModule()
        val source = application(library)
        val app = source.modules.single()
        val mismatched =
            source.copy(
                modules =
                    listOf(
                        app.copy(
                            types =
                                app.types.toMutableList().also {
                                    it[1] = (it[1] as NominalType.Function).copy(result = ValueType.Bool)
                                },
                        ),
                    ),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                LibraryModuleLinker.link(mismatched, mapOf("sample" to library))
            }

        assertTrue(failure.message.orEmpty().contains("resolves to 0 exports"))
    }

    private fun application(library: Module): Artifact {
        val libraryHash = ArtifactWriter.moduleSemanticHash(library)
        val app =
            Module(
                name = StringId.of(0u),
                kind = ModuleKind.APPLICATION,
                strings = listOf("app", "entry", "live").map(MetadataText::of),
                types =
                    listOf(
                        signature(StringId.of(1u)),
                        signature(StringId.of(2u)),
                    ),
                imports =
                    listOf(
                        Import(
                            kind = SymbolKind.FUNCTION,
                            targetModule = ModuleId.of(99u),
                            targetName = StringId.of(2u),
                            expectedSignature = TypeRef.Local(TypeId.of(1u)),
                            targetModuleHash = libraryHash,
                        ),
                    ),
                functions = listOf(function(StringId.of(1u), TypeId.of(0u), FunctionId.of(0u), BlockId.of(0u))),
                blocks =
                    listOf(
                        Block(
                            owner = FunctionId.of(0u),
                            loopHeaderSafepoint = false,
                            instructions =
                                listOf(
                                    Instruction.Call(Destination.Unit, FunctionRef.Imported(ImportId.of(0u)), emptyList()),
                                    Instruction.Return(Destination.Unit),
                                ),
                        ),
                    ),
            )
        return Artifact(
            semanticFeatures = setOf(SemanticFeature.MODULE_IMPORTS),
            manifest = Manifest.minimal(maximumBlockCost = 64u, minimumSliceCost = 64u),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(0u)),
            modules = listOf(app),
        )
    }

    private fun libraryModule(): Module =
        Module(
            name = StringId.of(2u),
            kind = ModuleKind.LIBRARY,
            strings = listOf("dead", "live", "sample-library").map(MetadataText::of),
            types = listOf(signature(StringId.of(1u)), signature(StringId.of(1u)).copy(result = ValueType.I32)),
            constants = listOf(Constant.I32(3), Constant.I32(7)),
            exports =
                listOf(
                    Export(SymbolKind.FUNCTION, ExportVisibility.PUBLIC_LIBRARY, StringId.of(1u), 0u, TypeRef.Local(TypeId.of(0u))),
                    Export(SymbolKind.FUNCTION, ExportVisibility.PUBLIC_LIBRARY, StringId.of(1u), 1u, TypeRef.Local(TypeId.of(1u))),
                ),
            functions =
                listOf(
                    function(StringId.of(1u), TypeId.of(0u), FunctionId.of(0u), BlockId.of(0u), ConstantId.of(1u), recursive = true),
                    function(StringId.of(1u), TypeId.of(1u), FunctionId.of(1u), BlockId.of(1u), ConstantId.of(0u)),
                ),
            blocks =
                listOf(
                    block(FunctionId.of(0u), ConstantId.of(1u), recursive = true),
                    block(FunctionId.of(1u), ConstantId.of(0u)),
                ),
        )

    private fun facadeModule(dependencyHash: ByteArray): Module =
        Module(
            name = StringId.of(0u),
            kind = ModuleKind.LIBRARY,
            strings = listOf("alpha-library", "live").map(MetadataText::of),
            types = listOf(signature(StringId.of(1u))),
            imports =
                listOf(
                    Import(
                        SymbolKind.FUNCTION,
                        ModuleId.of(99u),
                        StringId.of(1u),
                        TypeRef.Local(TypeId.of(0u)),
                        dependencyHash,
                    ),
                ),
            exports =
                listOf(
                    Export(SymbolKind.FUNCTION, ExportVisibility.PUBLIC_LIBRARY, StringId.of(1u), 0u, TypeRef.Local(TypeId.of(0u))),
                ),
            functions = listOf(function(StringId.of(1u), TypeId.of(0u), FunctionId.of(0u), BlockId.of(0u))),
            blocks =
                listOf(
                    Block(
                        FunctionId.of(0u),
                        false,
                        listOf(
                            Instruction.Call(Destination.Unit, FunctionRef.Imported(ImportId.of(0u)), emptyList()),
                            Instruction.Return(Destination.Unit),
                        ),
                    ),
                ),
        )

    private fun signature(name: StringId): NominalType.Function =
        NominalType.Function(name, suspending = false, result = ValueType.Unit, parameters = emptyList())

    private fun function(
        name: StringId,
        signature: TypeId,
        owner: FunctionId,
        block: BlockId,
        constant: ConstantId? = null,
        recursive: Boolean = false,
    ): Function =
        Function(
            owner = null,
            name = name,
            signature = TypeRef.Local(signature),
            flags = setOf(FunctionFlag.STATIC),
            registers = if (constant == null) emptyList() else listOf(ValueType.I32),
            parameterCount = 0u,
            firstBlock = block,
            blockCount = 1u,
            firstException = 0u,
            exceptionCount = 0u,
        ).also {
            require(owner.value == block.value)
            require(!recursive || constant != null)
        }

    private fun block(
        owner: FunctionId,
        constant: ConstantId,
        recursive: Boolean = false,
    ): Block =
        Block(
            owner,
            false,
            listOf(Instruction.Const(RegisterId.of(0u), constant)) +
                listOfNotNull(
                    Instruction.Call(Destination.Unit, FunctionRef.Local(owner), emptyList()).takeIf { recursive },
                ) + Instruction.Return(Destination.Unit),
        )

    private fun moduleName(module: Module): String = module.strings[module.name.value.toInt()].toString()
}
