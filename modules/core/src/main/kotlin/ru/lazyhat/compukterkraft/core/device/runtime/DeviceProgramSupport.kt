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

import ru.lazyhat.compukterkraft.core.language.LanguageServices
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.api.BytecodeClass
import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.BytecodeRecord
import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.frontend.FrontendSeverity
import ru.lazyhat.compukterkraft.lang.frontend.LanguageBuiltins
import ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontend
import ru.lazyhat.compukterkraft.lang.frontend.CompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.frontend.NoOpCompilerMetricsCollector
import ru.lazyhat.compukterkraft.lang.frontend.NoOpSourceLoader
import ru.lazyhat.compukterkraft.lang.frontend.SourceLoader
import ru.lazyhat.compukterkraft.lang.runtime.BytecodeComputerProgram
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProgram
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceSourceLoader

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
        val artifact = LanguageFrontend(runtimeRegistry, compilerMetricsCollector).compile(path, source, sourceLoader)
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
        } else if (profile != null && module.estimatedRomBytes() > profile.resources.storage.programRomBytes) {
            CompiledComputerProgram(
                program = null,
                errorMessage = "Program exceeds ROM limit: ${module.estimatedRomBytes()} > ${profile.resources.storage.programRomBytes}",
            )
        } else {
            CompiledComputerProgram(program = BytecodeComputerProgram(module))
        }
    }
}

private fun BytecodeModule.estimatedRomBytes(): Long =
    name.length.toLong() +
        16L +
        functions.sumOf(BytecodeFunction::estimatedRomBytes) +
        records.sumOf(BytecodeRecord::estimatedRomBytes) +
        classes.sumOf(BytecodeClass::estimatedRomBytes)

private fun BytecodeFunction.estimatedRomBytes(): Long =
    name.length.toLong() +
        returnType.length +
        24L +
        parameters.sumOf { it.name.length.toLong() + it.typeName.length } +
        locals.sumOf { it.name.length.toLong() + it.typeName.length } +
        instructions.sumOf(Instruction::estimatedRomBytes)

private fun BytecodeRecord.estimatedRomBytes(): Long =
    name.length.toLong() + 8L + fields.sumOf { it.name.length.toLong() + it.typeName.length }

private fun BytecodeClass.estimatedRomBytes(): Long =
    name.length.toLong() +
        12L +
        fields.sumOf { it.name.length.toLong() + it.typeName.length + 1L } +
        instanceMethods.entries.sumOf { it.key.length.toLong() + 4L } +
        staticMethods.entries.sumOf { it.key.length.toLong() + 4L } +
        if (initFunctionIndex != null) 4L else 0L

private fun Instruction.estimatedRomBytes(): Long =
    when (this) {
        is Instruction.Binary -> 4L
        is Instruction.CallBuiltin -> 12L + functionName.length + (moduleName?.length ?: 0)
        is Instruction.CallFunction -> 8L
        is Instruction.CallMethod -> 8L + methodName.length
        is Instruction.CallStaticMethod -> 12L + className.length + methodName.length
        is Instruction.ConstructClass -> 8L + className.length + fieldNames.sumOf(String::length)
        is Instruction.ConstructRecord -> 8L + typeName.length + fieldNames.sumOf(String::length)
        is Instruction.GetField -> 4L + fieldName.length
        is Instruction.Jump -> 4L
        is Instruction.JumpIfFalse -> 4L
        is Instruction.JumpIfTrue -> 4L
        is Instruction.LoadLocal -> 4L
        Instruction.Pop -> 2L
        is Instruction.PushBool -> 2L
        is Instruction.PushInt -> 8L
        is Instruction.PushLong -> 12L
        Instruction.PushNull -> 2L
        is Instruction.PushString -> 8L + value.length
        Instruction.PushUnit -> 2L
        Instruction.Return -> 2L
        is Instruction.SetField -> 4L + fieldName.length
        is Instruction.StoreLocal -> 4L
        is Instruction.Unary -> 4L
    }
