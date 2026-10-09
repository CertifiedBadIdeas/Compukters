/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.ide.project

import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectCatalogTest {
    @Test
    fun `owned folders rename without overwriting and stale handles cannot delete replacements`() {
        val root = createTempDirectory("compukters-managed-")
        val catalog = ProjectCatalog.open(root)
        val original = catalog.create("first")
        catalog.create("occupied")
        assertFailsWith<ProjectCatalogException> { catalog.rename(original, "occupied") }
        assertTrue(original.handle.isValid())
        val renamed = catalog.rename(original, "second")
        assertFalse(original.handle.isValid())
        assertEquals(root.resolve("second"), renamed.handle.canonicalPath)
        assertTrue(renamed.handle.isValid())
        val replacement = catalog.create("first")
        assertFailsWith<ProjectCatalogException> { catalog.remove(original) }
        assertTrue(replacement.handle.isValid())
        catalog.remove(renamed)
        assertFalse(root.resolve("second").exists())
        assertEquals(setOf("first", "occupied"), catalog.projects().map { it.directoryName }.toSet())
    }

    @Test
    fun `forgetting an external project persists without modifying its files`() {
        val root = createTempDirectory("compukters-registered-")
        val external = ProjectCatalog.open(createTempDirectory("compukters-external-")).create("external")
        val catalog = ProjectCatalog.open(root)
        val registered = catalog.register(external.handle.canonicalPath)
        assertTrue(registered.external)
        assertEquals(listOf(registered.directoryName), ProjectCatalog.open(root).projects().map { it.directoryName })
        assertFailsWith<IllegalArgumentException> { catalog.rename(registered, "renamed") }
        val manifest =
            external.handle.canonicalPath
                .resolve("compukter.toml")
                .readText()
        catalog.remove(registered)
        assertTrue(ProjectCatalog.open(root).projects().isEmpty())
        assertTrue(external.handle.isValid())
        assertEquals(
            manifest,
            external.handle.canonicalPath
                .resolve("compukter.toml")
                .readText(),
        )
        assertTrue(
            external.handle.canonicalPath
                .resolve("src/main.kt")
                .exists(),
        )
    }

    @Test
    fun `owned deletion removes symbolic links without touching their targets`() {
        val root = createTempDirectory("compukters-managed-")
        val outside = createTempDirectory("compukters-outside-")
        outside.resolve("keep.txt").writeText("keep")
        val catalog = ProjectCatalog.open(root)
        val project = catalog.create("demo")
        Files.createSymbolicLink(project.handle.canonicalPath.resolve("link"), outside)
        catalog.remove(project)
        assertEquals("keep", outside.resolve("keep.txt").readText())
    }

    @Test
    fun `imports publish validated projects and clean failed partial repositories`() {
        val root = createTempDirectory("compukters-project-import-")
        val catalog = ProjectCatalog.open(root)
        assertFailsWith<Exception> {
            catalog.importProject("broken") { destination ->
                destination
                    .resolve(".git")
                    .createDirectory()
                    .resolve("config")
                    .writeText("partial")
            }
        }
        assertTrue(root.listDirectoryEntries().isEmpty())
        val imported =
            catalog.importProject("clone") { destination ->
                destination.resolve("compukter.toml").writeText(ProjectManifestCodec.encode(ProjectManifest.of("imported", emptySet())))
                destination
                    .resolve("src")
                    .createDirectory()
                    .resolve("main.kt")
                    .writeText("fun main() {}")
            }
        assertEquals("imported", catalog.readManifest(imported.handle).name)
        assertEquals(root.resolve("clone"), imported.handle.canonicalPath)
        assertEquals(listOf("clone"), root.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `existing projects register in place persist and deduplicate without copying`() {
        val root = createTempDirectory("compukters-catalog-")
        val external = ProjectCatalog.open(createTempDirectory("compukters-external-")).create("existing")
        val catalog = ProjectCatalog.open(root)
        val registered = catalog.register(external.handle.canonicalPath)
        assertEquals(external.handle.canonicalPath, registered.handle.canonicalPath)
        assertEquals("existing", catalog.readManifest(registered.handle).name)
        assertEquals(registered.directoryName, catalog.register(external.handle.canonicalPath).directoryName)
        assertEquals(listOf(registered), ProjectCatalog.open(root).projects().map { it.copy(handle = registered.handle) })
        assertEquals(1, root.listDirectoryEntries().size)
        assertTrue(
            root
                .listDirectoryEntries()
                .single()
                .toFile()
                .isFile,
        )
        external.handle.canonicalPath.moveTo(external.handle.canonicalPath.resolveSibling("moved"))
        assertFalse(registered.handle.isValid())
        assertTrue(ProjectCatalog.open(root).projects().isEmpty())
    }

    @Test
    fun `invalid registration publishes nothing and owned project registration deduplicates`() {
        val root = createTempDirectory("compukters-catalog-invalid-")
        val catalog = ProjectCatalog.open(root)
        assertFailsWith<Exception> { catalog.register(createTempDirectory("compukters-no-manifest-")) }
        assertTrue(root.listDirectoryEntries().isEmpty())
        val owned = catalog.create("owned")
        assertEquals(owned.directoryName, catalog.register(owned.handle.canonicalPath).directoryName)
        assertEquals(1, catalog.projects().size)
        assertFailsWith<IllegalArgumentException> { catalog.create(".registered-user") }
    }

    @Test
    fun `catalog creates and lists canonical projects without a lock`() {
        val root = createTempDirectory("compukters-projects-")
        val catalog = ProjectCatalog.open(root)

        val zeta = catalog.create("zeta")
        val alpha = catalog.create("alpha")

        assertEquals(listOf("alpha", "zeta"), catalog.projects().map { it.directoryName })
        assertEquals("alpha", catalog.readManifest(alpha.handle).name)
        assertEquals(
            "fun main() {\n}\n",
            alpha.handle.canonicalPath
                .resolve("src/main.kt")
                .readText(),
        )
        assertTrue(
            alpha.handle.canonicalPath
                .resolve("compukter.toml")
                .exists(),
        )
        assertFalse(
            alpha.handle.canonicalPath
                .resolve("compukter.lock")
                .exists(),
        )
        assertTrue(alpha.handle.isValid())
        assertTrue(zeta.handle.isValid())
    }

    @Test
    fun `catalog rejects collisions invalid names and unsafe entries`() {
        val root = createTempDirectory("compukters-projects-invalid-")
        val catalog = ProjectCatalog.open(root)
        catalog.create("hello")

        assertFailsWith<ProjectCatalogException> { catalog.create("hello") }
        listOf("", ".", "..", "a/b", "a\\b", "Bad Name\u0000").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { catalog.create(name) }
        }

        val outside = createTempDirectory("compukters-projects-outside-")
        Files.createSymbolicLink(root.resolve("linked"), outside)
        assertFailsWith<ProjectCatalogException> { catalog.projects() }
    }

    @Test
    fun `creating a project does not reread a malformed sibling manifest`() {
        val root = createTempDirectory("compukters-projects-sibling-")
        val broken = root.resolve("p1").createDirectory()
        broken.resolve("compukter.toml").writeText(
            """
            format = 1
            name = "p1"

            [modules]
            compukter = { redstone = 2 }
            std = { terminal = 1 }
            """.trimIndent(),
        )
        val catalog = ProjectCatalog.open(root)

        val created = catalog.create("p2")

        assertEquals("p2", created.directoryName)
        assertTrue(created.handle.isValid())
        assertEquals(
            "fun main() {\n}\n",
            created.handle.canonicalPath
                .resolve("src/main.kt")
                .readText(),
        )
        val projects = catalog.projects()
        assertEquals(listOf("p1", "p2"), projects.map { it.directoryName })
        val failure = assertFailsWith<ProjectCatalogException> { catalog.readManifest(projects.first().handle) }
        assertTrue(failure.message.orEmpty().contains("p1: unknown manifest key: modules"))
    }

    @Test
    fun `importing a project does not reread a malformed sibling manifest`() {
        val root = createTempDirectory("compukters-import-sibling-")
        val broken = root.resolve("p1").createDirectory()
        broken.resolve("compukter.toml").writeText("format = 99\nname = \"p1\"\n")
        val catalog = ProjectCatalog.open(root)

        val imported =
            catalog.importProject("cloned") { destination ->
                destination.resolve("compukter.toml").writeText(ProjectManifestCodec.encode(ProjectManifest.of("cloned", emptySet())))
                destination
                    .resolve("src")
                    .createDirectory()
                    .resolve("main.kt")
                    .writeText("fun main() {}")
            }

        assertEquals("cloned", imported.directoryName)
        assertTrue(imported.handle.isValid())
        assertEquals(
            "fun main() {}",
            imported.handle.canonicalPath
                .resolve("src/main.kt")
                .readText(),
        )
        assertEquals(listOf("cloned", "p1"), catalog.projects().map { it.directoryName })
    }

    @Test
    fun `catalog lists malformed projects but rejects their manifest when selected`() {
        val root = createTempDirectory("compukters-projects-malformed-")
        val broken = root.resolve("broken").createDirectory()
        broken.resolve("compukter.toml").writeText("format = 99\nname = \"broken\"\n")

        val catalog = ProjectCatalog.open(root)
        val project = catalog.projects().single()
        assertEquals("broken", project.directoryName)
        assertFailsWith<ProjectCatalogException> { catalog.readManifest(project.handle) }
        val renamed = catalog.rename(project, "renamed")
        assertEquals("renamed", renamed.directoryName)
        catalog.remove(renamed)
        assertTrue(catalog.projects().isEmpty())
    }

    @Test
    fun `missing owned manifest and changed external manifest do not block catalog management`() {
        val root = createTempDirectory("compukters-projects-list-only-")
        val catalog = ProjectCatalog.open(root)
        val healthy = catalog.create("healthy")
        val missing = root.resolve("missing").createDirectory()
        val external = ProjectCatalog.open(createTempDirectory("compukters-external-manifest-")).create("outside")
        val registered = catalog.register(external.handle.canonicalPath)
        external.handle.canonicalPath
            .resolve("compukter.toml")
            .writeText("format = 99\nname = \"outside\"\n")

        val projects = catalog.projects()
        assertEquals(setOf("healthy", "missing", registered.directoryName), projects.map { it.directoryName }.toSet())
        assertEquals("healthy", catalog.readManifest(healthy.handle).name)
        assertFailsWith<Exception> { catalog.readManifest(projects.single { it.directoryName == "missing" }.handle) }
        assertFailsWith<ProjectCatalogException> { catalog.readManifest(projects.single { it.external }.handle) }
        catalog.remove(projects.single { it.external })
        assertTrue(
            external.handle.canonicalPath
                .resolve("compukter.toml")
                .exists(),
        )
        catalog.remove(projects.single { it.directoryName == "missing" })
        assertFalse(missing.exists())
        assertEquals(listOf("healthy"), catalog.projects().map { it.directoryName })
    }

    @Test
    fun `open handle is invalidated by removal rename and same-path replacement`() {
        val root = createTempDirectory("compukters-projects-identity-")
        val catalog = ProjectCatalog.open(root)
        val project = catalog.create("hello")
        val oldPath = project.handle.canonicalPath
        val moved = root.resolve("moved")

        oldPath.moveTo(moved)
        assertFalse(project.handle.isValid())

        oldPath.createDirectory()
        oldPath.resolve("compukter.toml").writeText(ProjectManifestCodec.encode(ProjectManifest.of("hello", emptySet())))
        oldPath.resolve("src").createDirectory()
        oldPath.resolve("src/main.kt").writeText("fun main() {}")
        assertFalse(project.handle.isValid())
        assertTrue(
            catalog
                .projects()
                .single { it.directoryName == "hello" }
                .handle
                .isValid(),
        )
    }

    @Test
    fun `failed staged creation leaves no partial project`() {
        ProjectCreationStep.entries.forEach { failingStep ->
            val root = createTempDirectory("compukters-projects-failure-")
            val catalog =
                ProjectCatalog.open(root) { step ->
                    if (step == failingStep) error("injected $step")
                }

            assertFailsWith<ProjectCatalogException>(failingStep.name) { catalog.create("hello") }
            assertEquals(emptyList(), root.listDirectoryEntries())
        }
    }
}
