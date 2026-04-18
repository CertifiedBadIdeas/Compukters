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
package ru.lazyhat.compukterkraft.core.computer.runtime

import ru.lazyhat.compukterkraft.core.computer.runtime.test.runtimeProfile
import ru.lazyhat.compukterkraft.core.computer.runtime.test.runtimeTestWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputerProgramSupportTest {
    @Test
    fun fixtureWritesProgramIntoIsolatedWorkspace() {
        runtimeTestWorkspace("fixture") { workspace ->
            workspace.writeProgram(7, "boot.ck", "fun main() { terminal.printLine(\"first\"); }")
            workspace.writeProgram(8, "boot.ck", "fun main() { terminal.printLine(\"second\"); }")

            val loader = WorkspaceProgramLoader(workspace.host)

            val first = loader.load(7, "boot.ck")
            val second = loader.load(8, "boot.ck")

            assertNotNull(first)
            assertNotNull(second)
            assertEquals("fun main() { terminal.printLine(\"first\"); }", first.source)
            assertEquals("fun main() { terminal.printLine(\"second\"); }", second.source)
        }
    }

    @Test
    fun loadsDocumentFromWorkspace() {
        runtimeTestWorkspace("program-loader") { workspace ->
            workspace.writeProgram(7, "shell.ck", "fun main() { }")
            val loader = WorkspaceProgramLoader(workspace.host)

            val program = loader.load(7, "shell.ck")

            assertNotNull(program)
            assertEquals("shell.ck", program.path)
            assertEquals("fun main() { }", program.source)
        }
    }

    @Test
    fun loadsDocumentFromNestedWorkspacePath() {
        runtimeTestWorkspace("program-loader-nested") { workspace ->
            workspace.writeProgram(7, "lib/shell.ck", "fun main() { }")
            val loader = WorkspaceProgramLoader(workspace.host)

            val program = loader.load(7, "lib/shell.ck")

            assertNotNull(program)
            assertEquals("lib/shell.ck", program.path)
            assertEquals("fun main() { }", program.source)
        }
    }

    @Test
    fun rejectsProgramPathTraversalOutsideWorkspace() {
        runtimeTestWorkspace("program-loader-sandbox") { workspace ->
            assertFailsWith<IllegalArgumentException> {
                workspace.writeProgram(7, "../shell.ck", "fun main() { }")
            }
        }
    }

    @Test
    fun normalizesAbsoluteLookingProgramPathInsideWorkspace() {
        runtimeTestWorkspace("program-loader-absolute") { workspace ->
            workspace.writeProgram(7, "/tmp/shell.ck", "fun main() { }")
            val loader = WorkspaceProgramLoader(workspace.host)

            val program = loader.load(7, "tmp/shell.ck")

            assertNotNull(program)
            assertEquals("tmp/shell.ck", program.path)
            assertEquals("fun main() { }", program.source)
        }
    }

    @Test
    fun rejectsProgramPathTraversalWhenLoadingProgram() {
        runtimeTestWorkspace("program-loader-load-traversal") { workspace ->
            val loader = WorkspaceProgramLoader(workspace.host)

            assertFailsWith<IllegalArgumentException> {
                loader.load(7, "../shell.ck")
            }
        }
    }

    @Test
    fun normalizesAbsoluteLookingProgramPathWhenLoadingProgram() {
        runtimeTestWorkspace("program-loader-load-absolute") { workspace ->
            workspace.writeProgram(7, "tmp/shell.ck", "fun main() { }")
            val loader = WorkspaceProgramLoader(workspace.host)

            val program = loader.load(7, "/tmp/shell.ck")

            assertNotNull(program)
            assertEquals("tmp/shell.ck", program.path)
            assertEquals("fun main() { }", program.source)
        }
    }

    @Test
    fun returnsNullWhenDocumentIsMissing() {
        runtimeTestWorkspace("program-loader") { workspace ->
            val loader = WorkspaceProgramLoader(workspace.host)

            val program = loader.load(7, "shell.ck")

            assertNull(program)
        }
    }

    @Test
    fun reportsCompilationErrorsWithoutProducingProgram() {
        val compiled = ComputerProgramCompiler.compile("broken.ck", "fun main() { val flag: Bool = 42; }")

        assertNull(compiled.program)
        assertTrue(compiled.errorMessage?.contains("Expected Bool") == true)
    }

    @Test
    fun rejectsProgramWhenCompiledBytecodeExceedsRomLimit() {
        val profile = runtimeProfile(programRomBytes = 1)

        val compiled = ComputerProgramCompiler.compile("tiny.ck", "fun main() { }", profile)

        assertNull(compiled.program)
        assertTrue(compiled.errorMessage?.contains("ROM") == true)
    }
}
