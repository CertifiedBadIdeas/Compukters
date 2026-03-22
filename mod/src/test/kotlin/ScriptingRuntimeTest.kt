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
import ru.lazyhat.compukterkraft.computer.vm.ComputerProfileRegistry
import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingJarLoader
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingPaths
import java.io.File
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
        val previousStdlibJar = System.getProperty(KOTLIN_STDLIB_JAR_PROPERTY)

        try {
            System.clearProperty(KOTLIN_STDLIB_JAR_PROPERTY)

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

                val stdlibJar = System.getProperty(KOTLIN_STDLIB_JAR_PROPERTY)
                assertNotNull(stdlibJar, "Expected scripting environment to set $KOTLIN_STDLIB_JAR_PROPERTY")
                assertTrue(File(stdlibJar).exists(), "Configured stdlib path does not exist: $stdlibJar")

                val profile = ComputerProfileRegistry.forFamily(ComputerFamily.ADVANCED)
                val bootScript = environment.bundledScript(profile.bootScriptName)

                assertNotNull(bootScript, "Failed to load bootScript")

                val compiledScript = runBlocking { environment.compiler.compile("test-bootscript", bootScript) }

                assertNull(compiledScript.exceptionMessage, "${compiledScript.exceptionMessage}")
            } finally {
                scriptingJarLoader.close()
            }
        } finally {
            if (previousStdlibJar == null) {
                System.clearProperty(KOTLIN_STDLIB_JAR_PROPERTY)
            } else {
                System.setProperty(KOTLIN_STDLIB_JAR_PROPERTY, previousStdlibJar)
            }
        }
    }

    private companion object {
        const val KOTLIN_STDLIB_JAR_PROPERTY = "kotlin.java.stdlib.jar"
    }
}
