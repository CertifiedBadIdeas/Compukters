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

package ru.lazyhat.compukters.ide.git

import org.eclipse.jgit.api.Git
import ru.lazyhat.compukters.ide.client.workspace.DefaultIdeWorkspace
import ru.lazyhat.compukters.ide.client.workspace.IdeSaveRequest
import ru.lazyhat.compukters.ide.client.workspace.ProjectFileOpenResult
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitWorkspaceIntegrationTest {
    @Test
    fun `queued save precedes staging and cloned project contains admitted compiler sources only`() {
        val root = createTempDirectory("compukters-git-workspace-")
        val backend = JGitBackend(GitLimits()) { }
        DefaultIdeWorkspace(root.resolve("projects"), gitBackend = backend).use { workspace ->
            val project = workspace.createProject("original").await()
            val path = ProjectPath.file("src/main.kt")
            workspace.git(project.handle, GitOperation.Init, null, GitCancellation()).await()
            val opened = workspace.open(project.handle, path).await() as ProjectFileOpenResult.Text
            val source = "fun main() { println(42) }\n"
            val saving = workspace.save(IdeSaveRequest(project.handle, path, opened.snapshot.revision, source))
            val staged = workspace.git(project.handle, GitOperation.Stage(path), null, GitCancellation())
            saving.await()
            staged.await()
            workspace.git(project.handle, GitOperation.Stage(ProjectPath.file("compukter.toml")), null, GitCancellation()).await()
            workspace
                .git(
                    project.handle,
                    GitOperation.Commit("saved source", "Player", "player@example.com"),
                    null,
                    GitCancellation(),
                ).await()
            Git.open(project.handle.canonicalPath.toFile()).use { git ->
                assertEquals(
                    source,
                    git.repository
                        .open(git.repository.resolve("HEAD:src/main.kt"))
                        .bytes
                        .decodeToString(),
                )
            }
            val clone =
                workspace
                    .cloneProject(
                        "cloned",
                        project.handle.canonicalPath
                            .toUri()
                            .toString(),
                        null,
                        GitCancellation(),
                    ).await()
            assertEquals(
                setOf("original", "cloned"),
                workspace
                    .projects()
                    .await()
                    .map { it.directoryName }
                    .toSet(),
            )
            // Even malformed Kotlin under Git metadata never reaches the compiler snapshot.
            clone.handle.canonicalPath
                .resolve(".git/hidden.kt")
                .writeText("invalid Kotlin")
            val input = workspace.buildInput(clone.handle).await()
            assertEquals(listOf("src/main.kt"), input.sources.sources.map { it.path.value })
            assertEquals(
                source,
                input.sources.sources
                    .single()
                    .content
                    .toByteArray()
                    .decodeToString(),
            )
            assertFalse(
                workspace
                    .tree(clone.handle)
                    .await()
                    .flatten()
                    .any { it.path.value.startsWith(".git/") },
            )
            val external = workspace.importProject(clone.handle.canonicalPath.toString()).await()
            assertEquals(clone.directoryName, external.directoryName)
            val lateCancellation =
                object : GitBackend by backend {
                    override fun clone(
                        remote: String,
                        destination: Path,
                        credentials: GitCredentials?,
                        cancellation: GitCancellation,
                    ) {
                        backend.clone(remote, destination, credentials, cancellation)
                        cancellation.cancel()
                    }
                }
            DefaultIdeWorkspace(root.resolve("late-projects"), gitBackend = lateCancellation).use { late ->
                assertFailsWith<ExecutionException> {
                    late
                        .cloneProject(
                            "late",
                            project.handle.canonicalPath
                                .toUri()
                                .toString(),
                            null,
                            GitCancellation(),
                        ).await()
                }
                assertTrue(late.projects().await().isEmpty())
                assertTrue(
                    root
                        .resolve("late-projects")
                        .toFile()
                        .listFiles()!!
                        .isEmpty(),
                )
            }
            val cancelled = GitCancellation().also { it.cancel() }
            assertFailsWith<ExecutionException> {
                workspace
                    .cloneProject(
                        "cancelled",
                        project.handle.canonicalPath
                            .toUri()
                            .toString(),
                        null,
                        cancelled,
                    ).await()
            }
            assertFalse(workspace.projects().await().any { it.directoryName == "cancelled" })
            assertTrue(
                root
                    .resolve("projects")
                    .toFile()
                    .listFiles()!!
                    .none { it.name.startsWith(".creating-") },
            )
        }
        root.toFile().deleteRecursively()
    }

    private fun <T> CompletableFuture<T>.await(): T = get(10, TimeUnit.SECONDS)
}
