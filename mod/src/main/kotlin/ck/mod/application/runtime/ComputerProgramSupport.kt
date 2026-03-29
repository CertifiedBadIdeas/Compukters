/*
 * The Compukter Kraft Developers
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
package ck.mod.application.runtime

import ck.lang.frontend.FrontendSeverity
import ck.lang.runtime.BytecodeComputerProgram
import ck.lang.runtime.ComputerProgram
import ck.lang.runtime.ComputerWorkspace
import ck.mod.language.LanguageServices

data class LoadedComputerProgramSource(
    val path: String,
    val source: String,
)

data class CompiledComputerProgram(
    val program: ComputerProgram?,
    val errorMessage: String? = null,
)

class WorkspaceProgramLoader(
    private val workspace: ComputerWorkspace,
) {
    fun load(
        computerId: Int,
        path: String,
    ): LoadedComputerProgramSource? {
        val document = workspace.readDocument(computerId, path) ?: return null
        return LoadedComputerProgramSource(document.path, document.text)
    }
}

object ComputerProgramCompiler {
    fun compile(
        path: String,
        source: String,
    ): CompiledComputerProgram {
        val artifact = LanguageServices.frontend.compile(path, source)
        val module = artifact.module
        val errorMessage =
            artifact.analysis.diagnostics
                .filter { it.severity == FrontendSeverity.ERROR }
                .joinToString { it.message }

        return if (module == null || errorMessage.isNotEmpty()) {
            CompiledComputerProgram(
                program = null,
                errorMessage = errorMessage.ifEmpty { "Compilation failed." },
            )
        } else {
            CompiledComputerProgram(program = BytecodeComputerProgram(module))
        }
    }
}
