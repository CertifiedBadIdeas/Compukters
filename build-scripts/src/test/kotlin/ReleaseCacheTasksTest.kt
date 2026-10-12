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

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReleaseCacheTasksTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun cleanupDiscoversOutputsAtExecutionAndPreservesSources() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val task = project.tasks.create("cleanOutputs", CleanWorkspace::class.java)
        task.workspaceRoot.set(project.layout.projectDirectory)
        temporary.resolve("build.gradle.kts").writeText("")
        task.cleanOutputs()
        val output = temporary.resolve("build/new-output").createDirectories()
        val source = temporary.resolve("src/build").createDirectories()
        task.cleanOutputs()
        assertFalse(output.toFile().exists())
        assertTrue(source.toFile().exists())
    }

    @Test
    fun sdkStagingPreservesPublishedRepositoryAndWritesSelectedVersions() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val tool = temporary.resolve("release.py").apply { writeText("release tool") }
        val sdk = temporary.resolve("sdk").createDirectories()
        val dependency = sdk.resolve("repository/dependency.jar").apply {
            parent.createDirectories()
            writeText("dependency")
        }
        val task = project.tasks.create("stageSdk", StageAddonSdk::class.java)
        task.sdkDirectory.set(sdk.toFile())
        task.releaseTools.from(tool.toFile())
        task.sdkVersion.set("1.2.0")
        task.modVersion.set("0.6.0")
        task.modBuildVersion.set("0.6.0-SNAPSHOT")
        task.stage()
        assertEquals("dependency", dependency.toFile().readText())
        assertEquals("release tool", sdk.resolve("tools/release.py").toFile().readText())
        assertEquals("format=1\nsdkVersion=1.2.0\nmodVersion=0.6.0\nmodBuildVersion=0.6.0-SNAPSHOT\n", sdk.resolve("sdk.properties").toFile().readText())
    }

    @Test
    fun releaseGateReadsGitStateAtEveryExecution() {
        val repository = temporary.resolve("repository").createDirectories()
        val runtime = temporary.resolve("runtime").createDirectories()
        fun git(directory: Path, vararg arguments: String) {
            val process = ProcessBuilder("git", *arguments).directory(directory.toFile()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
        }
        git(runtime, "init", "--quiet")
        git(runtime, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--allow-empty", "-m", "fixture")
        git(repository, "init", "--quiet")
        git(repository, "-c", "protocol.file.allow=always", "submodule", "add", runtime.toString(), "host/runtime")
        git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture")
        git(repository, "tag", "v0.6.0")
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val task = project.tasks.create("verifyRelease", VerifyUniversalReleaseState::class.java)
        task.repositoryRoot.set(repository.toFile())
        task.productVersion.set("0.6.0")
        task.runtimeBundlesConfigured.set(true)
        task.verify()
        repository.resolve("untracked.txt").writeText("new worktree change")
        assertThrows(IllegalArgumentException::class.java) { task.verify() }
    }
}
