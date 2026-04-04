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

import ck.mod.computer.vm.ComputerWorkspaceHost
import ck.mod.computer.vm.ComputerWorkspaceInitializer
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileComputerWorkspaceTest {
    @Test
    fun readAndWriteDocument() {
        withWorkspace { workspace, _ ->
            workspace.writeDocument(
                7,
                "test.ck",
                "import terminal;\nfun main() { terminal.printLine(\"hello\"); }",
            )

            val doc = workspace.readDocument(7, "test.ck")

            assertNotNull(doc)
            assertEquals("import terminal;\nfun main() { terminal.printLine(\"hello\"); }", doc.text)
        }
    }

    @Test
    fun isolatesSameComputerIdAcrossDifferentWorldRoots() {
        val worldOneRoot = createTempDirectory("compukterkraft-world-one")
        val worldTwoRoot = createTempDirectory("compukterkraft-world-two")

        try {
            val worldOne = ComputerWorkspaceHost(rootPath = worldOneRoot)
            val worldTwo = ComputerWorkspaceHost(rootPath = worldTwoRoot)

            worldOne.writeDocument(
                1,
                "startup.ck",
                "import terminal;\nfun main() { terminal.printLine(\"world one\"); }",
            )

            assertNotNull(worldOne.readDocument(1, "startup.ck"))
            assertNull(worldTwo.readDocument(1, "startup.ck"))
        } finally {
            worldOneRoot.toFile().deleteRecursively()
            worldTwoRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversalOutsideWorkspace() {
        withWorkspace { workspace, _ ->
            assertFailsWith<IllegalArgumentException> {
                workspace.writeDocument(
                    3,
                    "../escape.ck",
                    "import terminal;\nfun main() { terminal.printLine(\"nope\"); }",
                )
            }
        }
    }

    @Test
    fun rejectsWritesThatExceedDiskQuota() {
        val root = createTempDirectory("compukterkraft-quota")

        try {
            val workspace = ComputerWorkspaceHost(rootPath = root, defaultDiskQuotaBytes = 8)

            assertFailsWith<IllegalStateException> {
                workspace.writeDocument(7, "big.ck", "123456789")
            }

            assertNull(workspace.readDocument(7, "big.ck"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun withWorkspace(block: (ComputerWorkspaceHost, Path) -> Unit) {
        val root = createTempDirectory("compukterkraft-workspace")

        try {
            block(ComputerWorkspaceHost(rootPath = root), root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

class ComputerWorkspaceInitializerTest {
    @Test
    fun clonesAllRomScriptsIntoNewWorkspace() {
        val root = createTempDirectory("compukterkraft-init")
        try {
            val initializer = ComputerWorkspaceInitializer(root)
            initializer.ensureInitialized(1)

            val computerDir = root.resolve("1")
            assertTrue(computerDir.exists())
            assertTrue(computerDir.resolve("bios.ck").exists())
            assertTrue(computerDir.resolve("shell.ck").exists())
            assertTrue(computerDir.resolve("ls.ck").exists())
            assertTrue(computerDir.resolve("mkdir.ck").exists())
            assertTrue(computerDir.resolve("rmdir.ck").exists())
            assertTrue(computerDir.resolve("pwd.ck").exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun doesNotTouchExistingWorkspace() {
        val root = createTempDirectory("compukterkraft-init")
        try {
            val initializer = ComputerWorkspaceInitializer(root)
            initializer.ensureInitialized(2)

            val computerDir = root.resolve("2")
            val biosFile = computerDir.resolve("bios.ck")
            val originalContent = biosFile.readText()

            // Modify the file
            biosFile.writeText("custom bios")

            // Re-initialize — should NOT overwrite
            initializer.ensureInitialized(2)

            assertEquals("custom bios", biosFile.readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
