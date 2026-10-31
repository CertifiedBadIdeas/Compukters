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

import ru.lazyhat.compukters.compiler.project.ProjectSnapshotLoader
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.ide.project.document.DocumentSaveResult
import ru.lazyhat.compukters.ide.project.document.ProjectDocumentStore
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.ide.project.tree.ProjectMutationResult
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeStore
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Ordinary project acceptance without POSIX-only fixtures or elevated symlink privileges. */
class ProjectFilesystemPlatformTest {
    @Test
    fun `catalog documents source snapshot locks tree operations and reopen work together`() {
        val directory = createTempDirectory("compukters-проекты-")
        val catalog = ProjectCatalog.open(directory)
        val project = catalog.create("hello")
        val documents = ProjectDocumentStore(project.handle)
        val source = ProjectPath.source("src/main.kt")
        val original = documents.open(source)
        val updated = "fun main() { println(42) }\n"
        val saved = assertIs<DocumentSaveResult.Saved>(documents.save(source, original.revision, updated))
        assertIs<DocumentSaveResult.Conflict>(documents.save(source, original.revision, "stale"))
        assertEquals(saved.snapshot, documents.open(source))
        val snapshot = ProjectSnapshotLoader.loadSourceSet(project.handle.canonicalPath, WorkerLimits())
        assertEquals(listOf("src/main.kt"), snapshot.sources.map { it.path.value })
        assertEquals(
            updated,
            snapshot.sources
                .single()
                .content
                .toByteArray()
                .decodeToString(),
        )

        val locks = project.handle.lockFileWriter()
        locks.create("first\n".toByteArray())
        locks.update("replacement\n".toByteArray())
        assertEquals("replacement\n", documents.open(ProjectPath.file("compukter.lock")).text)

        val tree = ProjectTreeStore(project.handle)
        assertIs<ProjectMutationResult.Changed>(tree.createDirectory(ProjectPath.file("notes")))
        assertIs<ProjectMutationResult.Changed>(tree.createText(ProjectPath.file("notes/first.txt")))
        assertIs<ProjectMutationResult.Changed>(tree.rename(ProjectPath.file("notes/first.txt"), ProjectPath.file("notes/last.txt")))
        assertIs<ProjectMutationResult.Changed>(tree.delete(tree.admitDelete(ProjectPath.file("notes"))))

        val renamed = catalog.rename(project, "renamed")
        assertFalse(project.handle.isValid())
        val reopened = ProjectCatalog.open(directory).projects().single()
        assertEquals(renamed.handle.canonicalPath, reopened.handle.canonicalPath)
        assertEquals(updated, ProjectDocumentStore(reopened.handle).open(source).text)
        catalog.remove(renamed)
        assertEquals(0L, Files.list(directory).use { it.count() })
        Files.delete(directory)
    }
}
