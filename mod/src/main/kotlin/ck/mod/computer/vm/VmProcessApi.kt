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

package ck.mod.computer.vm

import ck.lang.runtime.ComputerProcessApi
import ck.lang.runtime.ComputerProfile
import ck.lang.runtime.ComputerRuntime
import ck.lang.runtime.ComputerTerminalApi
import ck.mod.application.runtime.ComputerProgramCompiler
import ck.mod.application.runtime.WorkspaceProgramLoader

internal class VmProcessApi(
    private val ctx: VmContext,
    private val initialArgument: String,
    private val computerId: Int,
    private val pathResolver: VmPathResolver,
    private val filesystemApi: VmFileSystemApi,
    private val programLoader: WorkspaceProgramLoader,
    private val profile: ComputerProfile,
    private val runtimeCreator: (String, String) -> ComputerRuntime,
    private val terminal: ComputerTerminalApi,
) : ComputerProcessApi {
    override val argument: String = initialArgument
    override val workingDirectory: String get() = pathResolver.workingDirectory

    override suspend fun changeDirectory(path: String): Boolean {
        val resolved = ctx.resolvePath(path)
        return filesystemApi.isDirectory(resolved).also { isDir ->
            if (isDir) pathResolver.updateWorkingDirectory(resolved)
        }
    }

    override suspend fun run(
        path: String,
        argument: String,
    ): Int {
        val resolved = path
        val programSource = programLoader.load(computerId, resolved) ?: run {
            ctx.log("VM[$computerId] missing program: $resolved")
            return 1
        }
        val compiledProgram = ComputerProgramCompiler.compile(programSource.path, programSource.source, profile)
        val program = compiledProgram.program
        if (program == null) {
            val message = compiledProgram.errorMessage.orEmpty()
            terminal.printLine("Compilation Error: $message")
            return 1
        }

        return try {
            program.run(runtimeCreator(workingDirectory, argument))
            0
        } catch (failure: Throwable) {
            terminal.printLine("Program error: ${failure.message ?: failure.javaClass.simpleName}")
            1
        }
    }
}
