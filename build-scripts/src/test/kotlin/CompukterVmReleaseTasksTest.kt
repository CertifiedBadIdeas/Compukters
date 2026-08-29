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

import org.gradle.api.tasks.Exec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CompukterVmReleaseTasksTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun registersEveryVmReleaseCommandAsAnExecTask() {
        val projectDirectory = temporaryDirectory.resolve("project").toFile().apply { mkdirs() }
        val vmRoot = temporaryDirectory.resolve("compukter-vm").toFile().apply { mkdirs() }
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()

        project.registerCompukterVmReleaseTasks(vmRoot)

        val expectedCommands =
            mapOf(
                "checkCompukterVmRelease" to listOf("cargo", "xtask", "check"),
                "bumpCompukterVmRevision" to listOf("cargo", "xtask", "bump", "revision"),
                "bumpCompukterVmAbi" to listOf("cargo", "xtask", "bump", "abi"),
                "releaseCompukterVm" to listOf("cargo", "xtask", "release"),
            )
        expectedCommands.forEach { (taskName, expectedCommand) ->
            val task = project.tasks.named(taskName, Exec::class.java).get()
            assertEquals("release", task.group)
            assertEquals(vmRoot, task.workingDir)
            assertEquals(expectedCommand, task.commandLine)
            assertSame(System.`in`, task.standardInput)
        }
    }
}
