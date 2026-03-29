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

import ck.mod.computer.vm.ComputerWorkspaceHost
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComputerProgramSupportTest {
    @Test
    fun loadsDocumentFromWorkspace() {
        val root = createTempDirectory("compukterkraft-program-loader")
        try {
            val workspace = ComputerWorkspaceHost(rootPath = root)
            root
                .resolve("7")
                .createDirectories()
                .resolve("shell.ck")
                .writeText("fun main() { }")
            val loader = WorkspaceProgramLoader(workspace)

            val program = loader.load(7, "shell.ck")

            assertNotNull(program)
            assertEquals("shell.ck", program.path)
            assertEquals("fun main() { }", program.source)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun returnsNullWhenDocumentIsMissing() {
        val root = createTempDirectory("compukterkraft-program-loader")
        try {
            val workspace = ComputerWorkspaceHost(rootPath = root)
            val loader = WorkspaceProgramLoader(workspace)

            val program = loader.load(7, "shell.ck")

            assertNull(program)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun reportsCompilationErrorsWithoutProducingProgram() {
        val compiled = ComputerProgramCompiler.compile("broken.ck", "fun main() { val flag: Bool = 42; }")

        assertNull(compiled.program)
        assertTrue(compiled.errorMessage?.contains("Expected Bool") == true)
    }
}
