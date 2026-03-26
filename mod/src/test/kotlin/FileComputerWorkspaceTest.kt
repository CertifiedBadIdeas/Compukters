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

import ru.lazyhat.compukterkraft.computer.vm.FileComputerWorkspace
import ru.lazyhat.compukterkraft.machine.ComputerProgramFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileComputerWorkspaceTest {
    @Test
    fun seedsBootScriptIntoNewWorkspace() {
        withWorkspace { workspace, _ ->
            workspace.ensureInitialized(7)

            val bootScript = workspace.readDocument(7, ComputerProgramFiles.BIOS_SCRIPT_NAME)

            assertNotNull(bootScript)
            assertEquals(DEFAULT_BIOS, bootScript.text)
        }
    }

    @Test
    fun preserveCustomizedBootScriptWhenReinitialized() {
        withWorkspace { workspace, _ ->
            workspace.ensureInitialized(7)
            workspace.writeDocument(7, ComputerProgramFiles.BIOS_SCRIPT_NAME, "import terminal;\nfun main() { terminal.printLine(\"custom bios\"); }")

            workspace.ensureInitialized(7)

            val bootScript = workspace.readDocument(7, ComputerProgramFiles.BIOS_SCRIPT_NAME)

            assertNotNull(bootScript)
            assertEquals("import terminal;\nfun main() { terminal.printLine(\"custom bios\"); }", bootScript.text)
        }
    }

    @Test
    fun isolatesSameComputerIdAcrossDifferentWorldRoots() {
        val worldOneRoot = createTempDirectory("compukterkraft-world-one")
        val worldTwoRoot = createTempDirectory("compukterkraft-world-two")

        try {
            val worldOne = createWorkspace(worldOneRoot)
            val worldTwo = createWorkspace(worldTwoRoot)

            worldOne.writeDocument(1, "startup.ck", "import terminal;\nfun main() { terminal.printLine(\"world one\"); }")

            assertNotNull(worldOne.readDocument(1, "startup.ck"))
            assertNull(worldTwo.readDocument(1, "startup.ck"))
            assertNotNull(worldTwo.readDocument(1, ComputerProgramFiles.BIOS_SCRIPT_NAME))
        } finally {
            worldOneRoot.toFile().deleteRecursively()
            worldTwoRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversalOutsideWorkspace() {
        withWorkspace { workspace, _ ->
            assertFailsWith<IllegalArgumentException> {
                workspace.writeDocument(3, "../escape.ck", "import terminal;\nfun main() { terminal.printLine(\"nope\"); }")
            }
        }
    }

    @Test
    fun seedsBootScriptWithRelativeWorkspaceRoot() {
        val relativeRoot = Path.of("build", "tmp", "relative-workspace-test")

        try {
            Files.createDirectories(relativeRoot)
            val workspace = createWorkspace(relativeRoot)

            workspace.ensureInitialized(9)

            val bootScript = workspace.readDocument(9, ComputerProgramFiles.BIOS_SCRIPT_NAME)
            assertNotNull(bootScript)
            assertEquals(DEFAULT_BIOS, bootScript.text)
        } finally {
            relativeRoot.toFile().deleteRecursively()
        }
    }

    private fun withWorkspace(block: (FileComputerWorkspace, java.nio.file.Path) -> Unit) {
        val root = createTempDirectory("compukterkraft-workspace")

        try {
            block(createWorkspace(root), root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun createWorkspace(root: java.nio.file.Path): FileComputerWorkspace =
        FileComputerWorkspace(
            rootPath = root,
            bundledScriptLoader = { relativePath ->
                if (relativePath == ComputerProgramFiles.BIOS_SCRIPT_NAME) DEFAULT_BIOS else null
            },
        )

    private companion object {
        const val DEFAULT_BIOS = "import terminal;\nfun main() { terminal.printLine(\"boot\"); }"
    }
}
