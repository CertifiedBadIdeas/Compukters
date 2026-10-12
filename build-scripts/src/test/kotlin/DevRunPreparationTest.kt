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

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DevRunPreparationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun clientSeedingPreservesEditedOptionsAndServerList() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val run = temporary.resolve("run/client").createDirectories()
        val options = run.resolve("options.txt").apply { writeText("guiScale:2\nenableVsync:true\n") }
        val servers = run.resolve("servers.dat").apply { writeText("custom server list") }
        val priorServers = servers.readBytes()
        val task = project.tasks.create("prepareClient", PrepareClientDev::class.java)
        task.runDirectories.set(listOf(project.layout.projectDirectory.dir("run/client")))
        task.seed()
        assertTrue("guiScale:2" in options.readText())
        assertTrue("enableVsync:true" in options.readText())
        assertTrue("soundCategory_music:0.0" in options.readText())
        assertArrayEquals(priorServers, servers.readBytes())
        servers.toFile().delete()
        task.seed()
        assertTrue(servers.toFile().isFile)
        assertFalse(priorServers.contentEquals(servers.readBytes()))
    }

    @Test
    fun serverSeedingPreservesManualProperties() {
        val project = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val run = temporary.resolve("run/server").createDirectories()
        val properties = run.resolve("server.properties").apply { writeText("max-players=3\n") }
        val task = project.tasks.create("prepareServer", PrepareServerDev::class.java)
        task.runDirectories.set(listOf(project.layout.projectDirectory.dir("run/server")))
        task.seed()
        assertTrue("max-players=3" in properties.readText())
        assertTrue("spawn-protection=0" in properties.readText())
        assertTrue("eula=true" in run.resolve("eula.txt").readText())
        assertTrue("DevA" in run.resolve("ops.json").readText())
    }
}
