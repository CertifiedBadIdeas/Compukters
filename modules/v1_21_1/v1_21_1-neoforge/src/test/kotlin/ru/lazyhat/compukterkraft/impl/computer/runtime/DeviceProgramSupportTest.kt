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
package ru.lazyhat.compukterkraft.impl.computer.runtime

import ru.lazyhat.compukterkraft.core.device.runtime.ComputerProgramCompiler
import ru.lazyhat.compukterkraft.core.device.runtime.WorkspaceProgramLoader
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceHost
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.image.CkVmImageComputerProgram
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceProgramSupportTest {
    @Test
    fun loadsDocumentFromWorkspace() {
        val root = createTempDirectory("compukterkraft-program-loader")
        try {
            val workspace = DeviceWorkspaceHost(rootPath = root)
            root
                .resolve("7")
                .createDirectories()
                .resolve("shell.ck")
                .writeText("pub fun main() { }")
            val loader = WorkspaceProgramLoader(workspace)

            val program = loader.load(7, "shell.ck")

            assertNotNull(program)
            assertEquals("shell.ck", program.path)
            assertEquals("pub fun main() { }", program.source)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun returnsNullWhenDocumentIsMissing() {
        val root = createTempDirectory("compukterkraft-program-loader")
        try {
            val workspace = DeviceWorkspaceHost(rootPath = root)
            val loader = WorkspaceProgramLoader(workspace)

            val program = loader.load(7, "shell.ck")

            assertNull(program)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun reportsCompilationErrorsWithoutProducingProgram() {
        val compiled = ComputerProgramCompiler.compile("broken.ck", "pub fun main() { val flag: Bool = 42; }")

        assertNull(compiled.program)
        assertTrue(compiled.errorMessage?.contains("Expected Bool") == true)
    }

    @Test
    fun compilesSupportedSourceToImageProgram() {
        val compiled = ComputerProgramCompiler.compile("tiny.ck", "pub fun main() { }")

        assertTrue(compiled.program is CkVmImageComputerProgram)
        assertNull(compiled.errorMessage)
    }

    @Test
    fun rejectsProgramWhenCompiledImageExceedsRomLimit() {
        val profile =
            DeviceProfile(
                id = "test",
                displayName = "Test",
                cpuBudgetNanosPerSlice = 1_000_000,
                maxEventQueueSize = 16,
                resources =
                    DeviceResources(
                        cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                        memory = DeviceMemoryResources(),
                        storage = DeviceStorageResources(programRomBytes = 1, diskBytes = 1024),
                        queues = DeviceQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                    ),
            )

        val compiled = ComputerProgramCompiler.compile("tiny.ck", "pub fun main() { }", profile)

        assertNull(compiled.program)
        assertTrue(compiled.errorMessage?.contains("ROM") == true)
    }
}
