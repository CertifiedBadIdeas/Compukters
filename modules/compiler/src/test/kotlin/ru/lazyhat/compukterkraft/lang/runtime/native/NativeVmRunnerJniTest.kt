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

package ru.lazyhat.compukterkraft.lang.runtime.native

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.lang.api.BinaryOperator
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.api.BytecodeFunction
import ru.lazyhat.compukterkraft.lang.api.BytecodeModule
import ru.lazyhat.compukterkraft.lang.api.Instruction
import ru.lazyhat.compukterkraft.lang.runtime.RecordingRuntime
import kotlin.test.Test

class NativeVmRunnerJniTest {
    @Test
    fun nativeRunnerExecutesPureAdditionProgramWhenLibraryIsConfigured() {
        val libraryPath = System.getProperty("ckl.vm.native.library")?.takeIf { it.isNotBlank() } ?: return

        runBlocking {
            NativeVmRunner.fromLibraryPath(libraryPath).run(additionModule(), RecordingRuntime())
        }
    }

    private fun additionModule(): BytecodeModule =
        BytecodeModule(
            name = "native-smoke",
            functions =
                listOf(
                    BytecodeFunction(
                        name = "main",
                        parameters = emptyList(),
                        locals = emptyList(),
                        returnType = "Int",
                        instructions =
                            listOf(
                                Instruction.PushInt(1),
                                Instruction.PushInt(2),
                                Instruction.Binary(BinaryOperator.ADD),
                                Instruction.Return,
                            ),
                        sourceRange = null,
                    ),
                ),
            records = emptyList(),
            classes = emptyList(),
            entryFunctionIndex = 0,
            registry = BuiltinRegistry(modules = emptyList(), globals = emptyList(), builtinTypes = emptyList()),
        )
}
