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

import ru.lazyhat.compukters.compiler.artifact.model.Artifact
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.ModuleKind
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.write.ArtifactWriter
import java.util.IdentityHashMap

/**
 * Library-only metadata owned by one stable selection. Input artifacts must remain immutable for its lifetime.
 * Project modules and relocated copies are never retained here; each link owns their metadata separately.
 */
class PreparedLibraryModules private constructor(
    internal val artifacts: List<Artifact>,
    private val metadata: IdentityHashMap<Module, PreparedModuleMetadata>,
) {
    internal fun metadata(module: Module): PreparedModuleMetadata? = metadata[module]

    companion object {
        fun prepare(artifacts: List<Artifact>): PreparedLibraryModules = prepare(artifacts, ArtifactWriter::moduleSemanticHash)

        internal fun prepare(
            artifacts: List<Artifact>,
            calculate: (Module) -> ByteArray,
        ): PreparedLibraryModules {
            val metadata = IdentityHashMap<Module, PreparedModuleMetadata>()
            artifacts.forEach { artifact ->
                artifact.modules.filter { it.kind == ModuleKind.LIBRARY }.forEach { module ->
                    if (!metadata.containsKey(module)) {
                        metadata[module] = PreparedModuleMetadata(calculate(module).copyOf(), indexExports(module))
                    }
                }
            }
            return PreparedLibraryModules(artifacts.toList(), metadata)
        }
    }
}

internal class PreparedModuleMetadata(
    private val hash: ByteArray,
    val exports: Map<Pair<SymbolKind, String>, List<Export>>,
) {
    fun hash(): ByteArray = hash.copyOf()
}

/** Request-local entries supplement, but never grow, the prepared library metadata. */
internal class ModuleExportIndexes(
    private val prepared: PreparedLibraryModules? = null,
) {
    private val local = IdentityHashMap<Module, Map<Pair<SymbolKind, String>, List<Export>>>()

    fun find(
        module: Module,
        kind: SymbolKind,
        name: String,
    ): List<Export> {
        val index = prepared?.metadata(module)?.exports ?: local.getOrPut(module) { indexExports(module) }
        return index[kind to name].orEmpty()
    }
}

private fun indexExports(module: Module): Map<Pair<SymbolKind, String>, List<Export>> =
    module.exports.groupBy { it.kind to module.strings[it.name.value.toInt()].toString() }
