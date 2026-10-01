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
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.Field
import ru.lazyhat.compukters.compiler.artifact.model.FieldId
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
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
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibrarySpecializationsTest {
    private val names = setOf("Box<Int>")

    @Test
    fun `application capability names survive specialization metadata relocation`() {
        val source = module("app", ModuleKind.APPLICATION)
        val namespace = StringId.of(source.strings.indexOf(MetadataText.of("app")).toUInt())
        val name = StringId.of(source.strings.indexOf(MetadataText.of("value")).toUInt())
        val input = artifact(source).copy(capabilities = listOf(Capability(namespace, name, AbiVersion(1u, 0u), true, 1u)))
        val library = artifact(LibrarySpecializations.export(module("library", ModuleKind.LIBRARY), names))
        val reused = LibrarySpecializations.reuse(input, listOf(library), names)
        val descriptor = reused.capabilities.single()
        assertEquals("app", reused.modules[0].strings[descriptor.namespace.value.toInt()].toString())
        assertEquals("value", reused.modules[0].strings[descriptor.name.value.toInt()].toString())
    }

    @Test
    fun `trusted concrete types fields and methods redirect to one owner`() {
        val source = module("app", ModuleKind.APPLICATION)
        val library = LibrarySpecializations.export(module("library", ModuleKind.LIBRARY), names)
        val rewritten = LibrarySpecializations.reuse(artifact(source), listOf(artifact(library)), names).modules.single()
        assertEquals(3, rewritten.imports.size)
        val function = assertIs<Instruction.Call>(rewritten.blocks[1].instructions[1])
        assertIs<FunctionRef.Imported>(function.function)
        val allocation = assertIs<Instruction.NewObject>(rewritten.blocks[1].instructions[0])
        assertIs<TypeRef.Imported>(allocation.type)
        assertIs<TypeRef.Imported>(assertIs<ValueType.Ref>(rewritten.functions[1].values[0].semanticType).type)
        assertIs<FieldRef.Imported>(assertIs<Instruction.FieldGet>(rewritten.blocks[0].instructions[0]).field)
        assertEquals(setOf(SymbolKind.TYPE, SymbolKind.FUNCTION, SymbolKind.FIELD), rewritten.imports.map { it.kind }.toSet())
    }

    @Test
    fun `user type spelling alone never opts into library reuse`() {
        val source = artifact(module("app", ModuleKind.APPLICATION))
        val library = artifact(LibrarySpecializations.export(module("library", ModuleKind.LIBRARY), names))
        assertEquals(source, LibrarySpecializations.reuse(source, listOf(library), emptySet()))
        assertEquals(source, LibrarySpecializations.reuse(source, emptyList(), names))
    }

    @Test
    fun `inconsistent layouts incomplete methods and ambiguous owners are rejected`() {
        val source = artifact(module("app", ModuleKind.APPLICATION))
        val library = LibrarySpecializations.export(module("library", ModuleKind.LIBRARY), names)
        val changed = library.copy(fields = library.fields.map { it.copy(mutable = false) })
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                LibrarySpecializations.reuse(source, listOf(artifact(changed)), names)
            }.message.orEmpty().contains("inconsistent specialization layout"),
        )
        val missing = library.copy(exports = library.exports.filter { it.kind != SymbolKind.FUNCTION })
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                LibrarySpecializations.reuse(source, listOf(artifact(missing)), names)
            }.message.orEmpty().contains("incomplete specialization export"),
        )
        val second = LibrarySpecializations.export(module("other", ModuleKind.LIBRARY), names)
        for (dependencies in listOf(listOf(artifact(library), artifact(second)), listOf(artifact(second), artifact(library)))) {
            assertTrue(
                assertFailsWith<IllegalArgumentException> {
                    LibrarySpecializations.reuse(source, dependencies, names)
                }.message.orEmpty().contains("ambiguous specialization owner"),
            )
        }
    }

    @Test
    fun `duplicate copies of the same semantic owner are reused deterministically`() {
        val source = artifact(module("app", ModuleKind.APPLICATION))
        val library = artifact(LibrarySpecializations.export(module("library", ModuleKind.LIBRARY), names))
        assertContentEquals(
            ArtifactWriter.moduleSemanticHash(LibrarySpecializations.reuse(source, listOf(library), names).modules[0]),
            ArtifactWriter.moduleSemanticHash(LibrarySpecializations.reuse(source, listOf(library, library), names).modules[0]),
        )
    }

    @Test
    fun `dependent library redirects its variants without reexporting another owner`() {
        val anchor = module("anchor", ModuleKind.APPLICATION)
        val dependent = LibrarySpecializations.export(module("dependent", ModuleKind.LIBRARY), names)
        val owner = artifact(LibrarySpecializations.export(module("owner", ModuleKind.LIBRARY), names))
        val rewritten =
            LibrarySpecializations.reuse(
                artifact(anchor).copy(modules = listOf(anchor, dependent)),
                listOf(owner),
                names,
                definitionModule = 1,
            )
        assertEquals(anchor, rewritten.modules[0])
        assertTrue(rewritten.modules[1].exports.isEmpty())
        assertEquals(3, rewritten.modules[1].imports.size)
    }

    private fun artifact(module: Module): Artifact =
        Artifact(
            manifest = Manifest.minimal(64u),
            entry = EntryPoint(ModuleId.of(0u), FunctionId.of(1u)),
            modules = listOf(module),
        )

    private fun module(
        name: String,
        kind: ModuleKind,
    ): Module {
        val strings = listOf("Box<Int>", "entry", "get", name, "signature", "value").distinct().sorted().map(MetadataText::of)

        fun id(value: String) = StringId.of(strings.indexOf(MetadataText.of(value)).toUInt())
        val box = TypeRef.Local(TypeId.of(0u))
        val method = TypeRef.Local(TypeId.of(1u))
        val entry = TypeRef.Local(TypeId.of(2u))
        val reference = ValueType.Ref(false, box)
        return Module(
            name = id(name),
            kind = kind,
            strings = strings,
            types =
                listOf(
                    NominalType.Class(id("Box<Int>"), final = true, fieldStart = 0u, fieldCount = 1u, methodStart = 0u, methodCount = 1u),
                    NominalType.Function(id("signature"), false, ValueType.I32, listOf(reference)),
                    NominalType.Function(id("entry"), false, ValueType.Unit, emptyList()),
                ),
            fields = listOf(Field(box, id("value"), ValueType.I32, mutable = true, static = false)),
            functions =
                listOf(
                    Function(
                        box,
                        id("get"),
                        method,
                        emptySet(),
                        listOf(FunctionValue.scalar(reference), FunctionValue.scalar(ValueType.I32)),
                        1u,
                        BlockId.of(0u),
                        1u,
                        0u,
                        0u,
                    ),
                    Function(
                        null,
                        id("entry"),
                        entry,
                        setOf(FunctionFlag.STATIC),
                        listOf(FunctionValue.scalar(reference), FunctionValue.scalar(ValueType.I32)),
                        0u,
                        BlockId.of(1u),
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
                        listOf(
                            Instruction.FieldGet(RegisterId.of(1u), RegisterId.of(0u), FieldRef.Local(FieldId.of(0u))),
                            Instruction.Return(Destination.Register(RegisterId.of(1u))),
                        ),
                    ),
                    Block(
                        FunctionId.of(1u),
                        false,
                        listOf(
                            Instruction.NewObject(RegisterId.of(0u), box),
                            Instruction.Call(
                                Destination.Register(RegisterId.of(1u)),
                                FunctionRef.Local(FunctionId.of(0u)),
                                listOf(RegisterId.of(0u)),
                            ),
                            Instruction.Return(Destination.Unit),
                        ),
                    ),
                ),
        )
    }
}
