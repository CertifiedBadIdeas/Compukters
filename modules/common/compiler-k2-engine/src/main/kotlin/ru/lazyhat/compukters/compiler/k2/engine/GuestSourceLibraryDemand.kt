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

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.file

/** Select source-library owners after program and dependency IR have been combined. */
internal class GuestSourceLibraryDemand(
    module: IrModuleFragment,
    private val session: CompilationSession,
) {
    private fun project(declaration: IrDeclaration): Boolean = session.trustedPlatformModule(declaration.file.fileEntry.name) == null

    private val sourceDeclarationsByPath =
        session.sourcePlatformDeclarations.groupBy {
            "platform/${it.module.namespace}/${it.module.name}/${it.sourcePath}"
        }

    private fun admitted(declaration: IrDeclaration): Boolean {
        if (project(declaration)) return true
        if (session.virtualSourcePath(declaration.file.fileEntry.name) !in session.sourcePlatformPaths) return false
        val moduleId = session.trustedPlatformModule(declaration.file.fileEntry.name)
        val path = session.virtualSourcePath(declaration.file.fileEntry.name)?.value
        return sourceDeclarationsByPath[path].orEmpty().any { source ->
            source.module == moduleId &&
                source.endUtf16 > declaration.startOffset && source.startUtf16 < declaration.endOffset
        }
    }

    private val roots = GuestDeclarationGraph(module).let { graph -> graph.reachable(graph.roots.filter(::project), ::admitted) }

    fun includes(declaration: IrDeclaration): Boolean = project(declaration) || declaration.topLevelOwner() in roots
}
