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

package ru.lazyhat.compuktercraft.scripting.runtime

import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironment
import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironmentConfig
import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironmentInitializer
import java.io.File
import java.net.URLClassLoader

class ScriptingJarLoader(
    private val scriptingJar: File = ScriptingPaths.scriptingJar(),
    private val implementationClassName: String = "ru.lazyhat.compuktercraft.scripting.impl.ScriptingEnvironmentInitializerImpl",
) : AutoCloseable {
    var isLoaded = false
        private set

    var lastError: String? = null
        private set

    private var classLoader: URLClassLoader? = null

    fun hasScriptingJar(): Boolean = scriptingJar.exists()

    fun initialize(config: ScriptingEnvironmentConfig): ScriptingEnvironment? {
        if (!hasScriptingJar()) {
            lastError = "Scripting jar not found at ${scriptingJar.absolutePath}"
            return null
        }

        val currentThread = Thread.currentThread()
        val previousContextLoader = currentThread.contextClassLoader
        val parentLoader = ScriptingEnvironmentInitializer::class.java.classLoader

        return try {
            classLoader = URLClassLoader(arrayOf(scriptingJar.toURI().toURL()), parentLoader)
            currentThread.contextClassLoader = classLoader

            val initializer =
                Class
                    .forName(implementationClassName, true, classLoader)
                    .getDeclaredConstructor()
                    .newInstance() as ScriptingEnvironmentInitializer

            initializer.initialize(config).also {
                ScriptingEnvironmentHolder.install(it)
                isLoaded = true
                lastError = null
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            ScriptingEnvironmentHolder.clear()
            null
        } finally {
            currentThread.contextClassLoader = previousContextLoader
        }
    }

    override fun close() {
        try {
            ScriptingEnvironmentHolder.environment?.close()
        } finally {
            ScriptingEnvironmentHolder.clear()
            classLoader?.close()
            classLoader = null
            isLoaded = false
        }
    }
}
