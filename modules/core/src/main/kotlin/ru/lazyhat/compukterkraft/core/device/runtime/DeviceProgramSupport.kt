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
package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.frontend.CompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.NoOpCompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.frontend.NoOpSourceLoader
import ru.lazyhat.compukterkraft.lang.frontend.SourceLoader
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProgram
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceSourceLoader
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImage
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageAbi
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageComputerProgram
import ru.lazyhat.compukterkraft.lang.runtime.image.compileImage

data class LoadedComputerProgramSource(
    val path: String,
    val source: String,
)

data class CompiledComputerProgram(
    val program: DeviceProgram?,
    val errorMessage: String? = null,
)

class WorkspaceProgramLoader(
    private val workspace: DeviceWorkspace,
) {
    fun load(
        deviceId: Int,
        path: String,
    ): LoadedComputerProgramSource? {
        val document = workspace.readDocument(deviceId, path) ?: return null
        return LoadedComputerProgramSource(document.path, document.text)
    }

    fun sourceLoader(deviceId: Int): SourceLoader = DeviceWorkspaceSourceLoader(workspace, deviceId)
}

object ComputerProgramCompiler {
    fun compile(
        path: String,
        source: String,
        profile: DeviceProfile? = null,
        runtimeRegistry: BuiltinRegistry = LanguageBuiltins.defaultRuntimeRegistry,
        sourceLoader: SourceLoader = NoOpSourceLoader,
        compilerMetricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
    ): CompiledComputerProgram {
        val artifact = LanguageFrontend(runtimeRegistry, compilerMetricsCollector).compileImage(path, source, sourceLoader)
        val image = artifact.image
        val errorMessage =
            artifact.bytecode.analysis.diagnostics
                .filter { it.severity == FrontendSeverity.ERROR }
                .joinToString { it.message }

        return if (image == null || errorMessage.isNotEmpty()) {
            CompiledComputerProgram(
                program = null,
                errorMessage = errorMessage.ifEmpty { "Compilation failed." },
            )
        } else if (profile != null && image.estimatedRomBytes() > profile.resources.storage.programRomBytes) {
            CompiledComputerProgram(
                program = null,
                errorMessage = "Program exceeds ROM limit: ${image.estimatedRomBytes()} > ${profile.resources.storage.programRomBytes}",
            )
        } else {
            CompiledComputerProgram(program = CkVmImageComputerProgram(image))
        }
    }
}

private fun CkVmImage.estimatedRomBytes(): Long = CkVmImageAbi.encode(this).size.toLong()
