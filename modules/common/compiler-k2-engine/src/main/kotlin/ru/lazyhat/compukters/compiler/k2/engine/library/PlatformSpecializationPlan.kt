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

package ru.lazyhat.compukters.compiler.k2.engine.library

import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import ru.lazyhat.compukters.compiler.k2.engine.GuestDeclarationGraph
import ru.lazyhat.compukters.compiler.k2.engine.specializedPlatformBaseTypes
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.k2.build.CompiledPlatformMetadata

/** A precompiled library must not fix a concrete generic representation before the final program is known. */
internal object PlatformSpecializationPlan {
    fun defer(
        ir: IrModuleFragment,
        sourceModules: Map<String, PlatformModuleId>,
        builtins: PlatformModuleId,
        metadata: Map<PlatformModuleId, CompiledPlatformMetadata>,
    ): Map<PlatformModuleId, CompiledPlatformMetadata> {
        val graph = GuestDeclarationGraph(ir)
        val paths = ir.files.associateWith { matchPlatformSourcePath(it.fileEntry.name, sourceModules.keys) }
        val deferred =
            graph.requiringSpecialization {
                sourceModules.getValue(paths.getValue(it.file)) != builtins ||
                    (it as? IrDeclarationWithName)?.fqNameWhenAvailable?.asString() in specializedPlatformBaseTypes
            }
        val byPath = deferred.groupBy { paths.getValue(it.file) }
        return metadata.mapValues { (module, value) ->
            if (module == builtins) return@mapValues value
            val additional =
                value.declarations
                    .filter { declaration ->
                        !declaration.trustedExternal &&
                            byPath[declaration.sourcePath].orEmpty().any { root ->
                                declaration.endUtf16 > root.startOffset && declaration.endUtf16 <= root.endOffset
                            }
                    }.map { it.identity }
            value.copy(sourceDeclarations = (value.sourceDeclarations + additional).distinct())
        }
    }
}
