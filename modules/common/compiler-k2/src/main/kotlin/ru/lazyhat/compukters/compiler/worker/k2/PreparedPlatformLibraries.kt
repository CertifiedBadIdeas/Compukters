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

package ru.lazyhat.compukters.compiler.worker.k2

import ru.lazyhat.compukters.compiler.k2.engine.library.LoadedPlatformLibraries
import ru.lazyhat.compukters.compiler.k2.engine.library.loadPlatformLibraries
import ru.lazyhat.compukters.platform.bundle.PlatformBundle
import ru.lazyhat.compukters.platform.bundle.PlatformModule
import ru.lazyhat.compukters.platform.bundle.PlatformModuleGraph
import ru.lazyhat.compukters.platform.bundle.PlatformModuleId
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirBuildEnvironment
import ru.lazyhat.compukters.platform.k2.build.CompuktersFirModuleOutput

/** Owns only admitted library FIR; request-specific sources and IR never enter this environment. */
internal class PreparedPlatformLibraries private constructor(
    val libraries: LoadedPlatformLibraries,
    val sourceLibraries: List<PlatformModule>,
    val metadataModules: List<PlatformModule>,
    val sourceOutputs: List<CompuktersFirModuleOutput>,
    private val environment: CompuktersFirBuildEnvironment,
) : AutoCloseable {
    fun createProjectEnvironment(): CompuktersFirBuildEnvironment = environment.createGuestRequestEnvironment()

    override fun close() = environment.close()

    companion object {
        fun load(
            platform: PlatformBundle,
            selected: List<PlatformModule>,
        ): PreparedPlatformLibraries {
            val libraries = loadPlatformLibraries(selected)
            val sourceLibraries = selected.filter { it.id != platform.builtins.id && it.sourceDeclarations.isNotEmpty() }
            val metadataModules = selected - sourceLibraries.toSet()
            val environment = CompuktersFirBuildEnvironment.create()
            try {
                val outputs = linkedMapOf<PlatformModuleId, CompuktersFirModuleOutput>()
                sourceLibraries.forEach { library ->
                    val dependencyIds =
                        PlatformModuleGraph(platform)
                            .resolve(library.dependencies.filterNot { it == platform.builtins.id }.toSet())
                            .modules
                            .mapTo(mutableSetOf(), PlatformModule::id)
                    val dependencies = outputs.filterKeys { it in dependencyIds }.values.toList()
                    outputs[library.id] = environment.compileGuest(library.id, library.sources, metadataModules, dependencies)
                }
                return PreparedPlatformLibraries(libraries, sourceLibraries, metadataModules, outputs.values.toList(), environment)
            } catch (failure: Throwable) {
                environment.close()
                throw failure
            }
        }
    }
}
