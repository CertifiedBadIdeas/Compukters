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

package ru.lazyhat.compukters.compiler.artifact.analysis

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.PhysicalAtom
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.SafepointRoots
import ru.lazyhat.compukters.compiler.artifact.model.ValueComponent

object ReferenceLiveness {
    fun derive(module: Module): Module =
        module.copy(
            functions =
                module.functions.map { function ->
                    function.copy(safepointRoots = derive(module, function))
                },
        )

    fun derive(artifact: Artifact): Artifact =
        artifact.copy(
            modules = artifact.modules.map(::derive),
        )

    fun derive(
        module: Module,
        function: Function,
    ): List<SafepointRoots> =
        ValueLiveness.derive(module, function).map { boundary ->
            SafepointRoots(
                block = boundary.block,
                instructionBoundary = boundary.instruction,
                references = referenceComponents(function, boundary.before),
            )
        }
}

private fun referenceComponents(
    function: Function,
    liveValues: Set<RegisterId>,
): List<ValueComponent> =
    liveValues
        .sortedBy(RegisterId::value)
        .flatMap { value ->
            function.values
                .getOrNull(value.value.toInt())
                ?.physicalShape
                ?.components
                ?.mapIndexedNotNull { component, atom ->
                    ValueComponent(value, component.toUShort()).takeIf { atom == PhysicalAtom.REF32 }
                }.orEmpty()
        }
