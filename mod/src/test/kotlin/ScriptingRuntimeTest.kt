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

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.MOD_ID
import ru.lazyhat.compukterkraft.block.ComputerFamily
import ru.lazyhat.compukterkraft.computer.vm.FileComputerWorkspace
import ru.lazyhat.compukterkraft.computer.vm.ComputerProfileRegistry
import ru.lazyhat.compukterkraft.machine.ComputerFileSystemApi
import ru.lazyhat.compukterkraft.machine.ComputerPeripheralApi
import ru.lazyhat.compukterkraft.machine.ComputerProgram
import ru.lazyhat.compukterkraft.machine.ComputerRedstoneApi
import ru.lazyhat.compukterkraft.machine.ComputerRuntime
import ru.lazyhat.compukterkraft.machine.ComputerScriptBindings
import ru.lazyhat.compukterkraft.machine.ComputerSystemApi
import ru.lazyhat.compukterkraft.machine.ComputerTerminalApi
import ru.lazyhat.compukterkraft.machine.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.machine.VmEvent
import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingJarLoader
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingPaths
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

class ScriptingRuntimeTest {
    @Test
    fun verifyCompilation() {
        val jarFile = "run/$MOD_ID/${ScriptingPaths.SCRIPTING_JAR}"
        val scriptingJarLoader =
            ScriptingJarLoader(
                scriptingJar = File(jarFile),
            )
        val config =
            ScriptingEnvironmentConfig(
                modId = MOD_ID,
                bundledScriptsRoot = "rom",
                externalScriptsDirectory = ScriptingPaths.scriptsDirectory().absolutePath,
                definitions = listOf(ScriptDefinitionPresets.computerKts(MOD_ID)),
            )

        try {
            val environment = scriptingJarLoader.initialize(config)

            assertNotNull(environment, "Failed to initialize scripting environment ${scriptingJarLoader.lastError}")

            val profile = ComputerProfileRegistry.forFamily(ComputerFamily.ADVANCED)
            val workspaceRoot = createTempDirectory("compukterkraft-seeded-workspace")

            try {
                val workspace =
                    FileComputerWorkspace(
                        rootPath = workspaceRoot,
                        bundledScriptLoader = environment::bundledScript,
                    )

                workspace.ensureInitialized(1)
                val bootScriptDocument = workspace.readDocument(1, profile.bootScriptName)

                assertNotNull(bootScriptDocument, "Expected workspace boot script to be seeded")

                val compiledScript = runBlocking { environment.compiler.compile(bootScriptDocument.path, bootScriptDocument.text) }

                assertNull(compiledScript.exceptionMessage, "${compiledScript.exceptionMessage}")
                val compiledBootScript =
                    assertNotNull(compiledScript.value, "Expected boot script to compile successfully")

                val execution = compiledBootScript.execute(ComputerScriptBindings.toProperties(TestComputerRuntime(profile)))

                assertNull(execution.exceptionMessage, "${execution.exceptionMessage}")
                assertTrue(
                    actual = execution.value is ComputerProgram,
                    message =
                        "Expected boot script to return ComputerProgram, got ${execution.value?.javaClass} " +
                            "loaded by ${execution.value?.javaClass?.classLoader}",
                )
            } finally {
                workspaceRoot.toFile().deleteRecursively()
            }
        } finally {
            scriptingJarLoader.close()
        }
    }
}

private class TestComputerRuntime(
    override val profile: ru.lazyhat.compukterkraft.machine.ComputerProfile,
) : ComputerRuntime {
    override val system: ComputerSystemApi =
        object : ComputerSystemApi {
            override val computerId: Int = 1
            override val label: String? = "Test"
            override val currentTick: Long = 0L

            override fun queueEvent(
                name: String,
                arguments: List<Any?>,
            ) = Unit

            override fun shutdown() = Unit

            override fun reboot() = Unit

            override fun log(message: String) = Unit
        }

    override val terminal: ComputerTerminalApi =
        object : ComputerTerminalApi {
            override suspend fun write(text: String) = Unit

            override suspend fun printLine(text: String) = Unit

            override suspend fun clear() = Unit

            override suspend fun setCursor(
                x: Int,
                y: Int,
            ) = Unit
        }

    override val filesystem: ComputerFileSystemApi =
        object : ComputerFileSystemApi {
            override suspend fun exists(path: String): Boolean = false

            override suspend fun readText(path: String): String? = null

            override suspend fun writeText(
                path: String,
                text: String,
            ) = Unit

            override suspend fun list(path: String): List<ComputerWorkspaceEntry> = emptyList()
        }

    override val redstone: ComputerRedstoneApi =
        object : ComputerRedstoneApi {}

    override val peripherals: ComputerPeripheralApi =
        object : ComputerPeripheralApi {}

    override suspend fun pullEvent(filter: String?): VmEvent = error("Not used by this test")

    override suspend fun sleep(ticks: Long) = Unit

    override suspend fun yield() = Unit
}
