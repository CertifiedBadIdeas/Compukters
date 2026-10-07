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
import ru.lazyhat.compukters.ide.project.ProjectCatalog
import ru.lazyhat.compukters.ide.project.ProjectDescriptor
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JGitBackendTest {
    private val backend = JGitBackend(GitLimits()) { }

    @Test
    fun `status staging diff commits and initial unstage preserve independent index state`() {
        val project = ProjectCatalog.open(createTempDirectory("compukters-git-")).create("demo")
        assertFalse(run(project, GitOperation.Status).status.available)
        run(project, GitOperation.Init)
        assertTrue(run(project, GitOperation.Diff(ProjectPath.file("src/main.kt"))).diff!!.contains("+fun main"))
        run(project, GitOperation.Stage(ProjectPath.file("compukter.toml")))
        run(project, GitOperation.Stage(ProjectPath.file("src/main.kt")))
        run(project, GitOperation.Unstage(ProjectPath.file("compukter.toml")))
        val changes = run(project, GitOperation.Status).status.changes.associateBy { it.path.value }
        assertEquals("added", changes.getValue("src/main.kt").index)
        assertEquals(null, changes.getValue("compukter.toml").index)
        assertTrue(run(project, GitOperation.Diff(ProjectPath.file("src/main.kt"), staged = true)).diff!!.contains("fun main"))
        run(project, commit("initial source"))
        project.handle.canonicalPath
            .resolve("src/main.kt")
            .writeText("fun main() { println(1) }\n")
        run(project, GitOperation.Stage(ProjectPath.file("src/main.kt")))
        project.handle.canonicalPath
            .resolve("src/main.kt")
            .writeText("fun main() { println(2) }\n")
        val status = run(project, GitOperation.Status).status.changes.single { it.path.value == "src/main.kt" }
        assertEquals("modified", status.index)
        assertEquals("modified", status.workingTree)
        assertTrue(run(project, GitOperation.Diff(ProjectPath.file("src/main.kt"))).diff!!.contains("println(2)"))
        assertTrue(run(project, GitOperation.Diff(ProjectPath.file("src/main.kt"), staged = true)).diff!!.contains("println(1)"))
        run(project, commit("only staged content"))
        Git.open(project.handle.canonicalPath.toFile()).use { git ->
            val objectId = git.repository.resolve("HEAD:src/main.kt")
            assertTrue(String(git.repository.open(objectId).bytes).contains("println(1)"))
        }
        assertEquals(listOf("only staged content", "initial source"), run(project, GitOperation.History).history.map { it.message })
    }

    @Test
    fun `fast forward pull updates files but divergence preserves head index and working tree`() {
        val catalog = ProjectCatalog.open(createTempDirectory("compukters-git-projects-"))
        val seed = catalog.create("seed")
        run(seed, GitOperation.Init)
        run(seed, GitOperation.Stage(ProjectPath.file("src")))
        run(seed, GitOperation.Stage(ProjectPath.file("compukter.toml")))
        run(seed, commit("baseline"))
        val remote = createTempDirectory("compukters-git-remote-")
        Git
            .init()
            .setBare(true)
            .setInitialBranch("main")
            .setDirectory(remote.toFile())
            .call()
            .use { }
        Git.open(seed.handle.canonicalPath.toFile()).use { it.push().setRemote(remote.toUri().toString()).call() }

        fun clone(): ProjectDescriptor {
            val destination = createTempDirectory("compukters-git-clone-")
            backend.clone(remote.toUri().toString(), destination, null, GitCancellation())
            return catalog.register(destination)
        }
        val first = clone()
        val second = clone()
        change(first, "remote update")
        run(first, GitOperation.Push)
        run(second, GitOperation.Pull)
        assertEquals(
            first.handle.canonicalPath
                .resolve("src/main.kt")
                .readText(),
            second.handle.canonicalPath
                .resolve("src/main.kt")
                .readText(),
        )
        change(first, "another remote update")
        run(first, GitOperation.Push)
        change(second, "independent local update")
        val before = run(second, GitOperation.Status).status.head
        val index = Files.readAllBytes(second.handle.canonicalPath.resolve(".git/index"))
        val text =
            second.handle.canonicalPath
                .resolve("src/main.kt")
                .readText()
        assertFailsWith<IllegalStateException> { run(second, GitOperation.Pull) }
        assertEquals(before, run(second, GitOperation.Status).status.head)
        assertTrue(index.contentEquals(Files.readAllBytes(second.handle.canonicalPath.resolve(".git/index"))))
        assertEquals(
            text,
            second.handle.canonicalPath
                .resolve("src/main.kt")
                .readText(),
        )
        assertFailsWith<IllegalStateException> { run(second, GitOperation.Push) }
    }

    @Test
    fun `branch changes reject dirty files and cancellation prevents operations`() {
        val project = ProjectCatalog.open(createTempDirectory("compukters-git-branches-")).create("demo")
        run(project, GitOperation.Init)
        run(project, GitOperation.Stage(ProjectPath.file("src")))
        run(project, GitOperation.Stage(ProjectPath.file("compukter.toml")))
        run(project, commit("initial"))
        run(project, GitOperation.CreateBranch("feature"))
        assertEquals("feature", run(project, GitOperation.Status).status.branch)
        project.handle.canonicalPath
            .resolve("src/main.kt")
            .writeText("fun main() { println(42) }")
        assertFailsWith<IllegalStateException> { run(project, GitOperation.SwitchBranch("main")) }
        assertEquals("feature", run(project, GitOperation.Status).status.branch)
        val cancelled = GitCancellation().apply { cancel() }
        assertFailsWith<IllegalStateException> {
            backend.execute(
                project.handle,
                GitOperation.Stage(ProjectPath.file("src")),
                null,
                cancelled,
            )
        }
        assertEquals(
            null,
            run(project, GitOperation.Status)
                .status.changes
                .single()
                .index,
        )
    }

    @Test
    fun `production remote admission rejects local insecure and credential bearing URLs`() {
        listOf(
            "http://example.org/a.git",
            "ssh://example.org/a.git",
            "file:///tmp/a",
            "https://user:secret@example.org/a.git",
            "https://example.org/a.git?token=secret",
        ).forEach {
            assertFailsWith<IllegalArgumentException> { GitRemote.requireHttps(it) }
        }
        GitRemote.requireHttps("https://example.org/a.git")
        val credentials = GitCredentials("user", "secret".toCharArray())
        assertFalse(credentials.toString().contains("secret"))
        val copy = credentials.token()
        copy.fill('x')
        assertEquals("secret", String(credentials.token()))
        credentials.close()
        assertTrue(credentials.token().all { it == '\u0000' })
    }

    private fun run(
        project: ProjectDescriptor,
        operation: GitOperation,
    ) = backend.execute(project.handle, operation, null, GitCancellation())

    private fun commit(message: String) = GitOperation.Commit(message, "IDE test", "ide-test@example.invalid")

    private fun change(
        project: ProjectDescriptor,
        message: String,
    ) {
        project.handle.canonicalPath
            .resolve("src/main.kt")
            .writeText("fun main() { println(\"$message\") }\n")
        run(project, GitOperation.Stage(ProjectPath.file("src/main.kt")))
        run(project, commit(message))
    }
}
