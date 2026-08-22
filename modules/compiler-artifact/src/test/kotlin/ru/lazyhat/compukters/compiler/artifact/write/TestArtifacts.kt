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

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.EntryPoint
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.Manifest
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleId
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType

internal fun minimalArtifact(
    instructions: List<Instruction> = listOf(Instruction.Return(Destination.Unit)),
): Artifact =
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
                                instructions = instructions,
                            ),
                        ),
                ),
            ),
    )
