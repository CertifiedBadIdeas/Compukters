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
package ru.lazyhat.compukterkraft.core.application.runtime

import ru.lazyhat.compukterkraft.core.computer.vm.ComputerWorkspaceHost
import ru.lazyhat.compukterkraft.lang.runtime.ComputerCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerProfile
import ru.lazyhat.compukterkraft.lang.runtime.ComputerQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerResources
import ru.lazyhat.compukterkraft.lang.runtime.ComputerStorageResources
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

    @Test
    fun rejectsProgramWhenCompiledBytecodeExceedsRomLimit() {
        val profile =
            ComputerProfile(
                id = "test",
                displayName = "Test",
                cpuBudgetNanosPerSlice = 1_000_000,
                maxEventQueueSize = 16,
                terminalWidth = 16,
                terminalHeight = 8,
                colorTerminal = true,
                resources =
                    ComputerResources(
                        cpu = ComputerCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                        memory = ComputerMemoryResources(),
                        storage = ComputerStorageResources(programRomBytes = 1, diskBytes = 1024),
                        queues = ComputerQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                    ),
            )

        val compiled = ComputerProgramCompiler.compile("tiny.ck", "fun main() { }", profile)

        assertNull(compiled.program)
        assertTrue(compiled.errorMessage?.contains("ROM") == true)
    }
}
