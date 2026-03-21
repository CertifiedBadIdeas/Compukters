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

package ru.lazyhat.compukterkraft.scripting.impl

import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertTrue

class ScriptingEnvironmentImplTest {
    @Test
    fun compileUsesClassLoaderCapturedDuringInitialization() {
        val thread = Thread.currentThread()
        val previousClassLoader = thread.contextClassLoader

        thread.contextClassLoader = javaClass.classLoader

        try {
            val environment =
                ScriptingEnvironmentInitializerImpl().initialize(
                    ScriptingEnvironmentConfig(
                        modId = "compukterkraft",
                        bundledScriptsRoot = "unused",
                        definitions = listOf(ScriptDefinitionPresets.standardKts("compukterkraft")),
                    ),
                )

            thread.contextClassLoader = URLClassLoader(emptyArray(), null)

            val result = environment.compiler.compile("bootstrap.kts", """println("Computer started!")""")

            assertTrue(
                actual = result.isSuccess,
                message = "Expected compilation to be independent from later thread context class loader changes, but got diagnostics: ${result.diagnostics}",
            )
        } finally {
            thread.contextClassLoader = previousClassLoader
        }
    }
}
