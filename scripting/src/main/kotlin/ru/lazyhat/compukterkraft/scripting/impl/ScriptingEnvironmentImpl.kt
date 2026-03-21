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

import ru.lazyhat.compukterkraft.scripting.api.ScriptCompiler
import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionDescriptor
import ru.lazyhat.compukterkraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compukterkraft.scripting.api.ScriptIdeService
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironment
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm

class ScriptingEnvironmentImpl(
    override val config: ScriptingEnvironmentConfig,
) : ScriptingEnvironment {
    override val definitions: List<ScriptDefinitionDescriptor> =
        config.definitions.ifEmpty { listOf(ScriptDefinitionPresets.standardKts(config.modId)) }

    val hostConfiguration = ScriptingHostConfiguration {}
    private val runtimeClassLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: javaClass.classLoader

    val defaultImports: List<String>
        get() = definitions.flatMap { it.defaultImports }.distinct()

    override val compiler: ScriptCompiler = ScriptCompilerImpl(this)
    override val ide: ScriptIdeService = ScriptIdeServiceImpl(this)
    override val isAvailable: Boolean = true

    fun compilationConfiguration(scriptName: String): ScriptCompilationConfiguration =
        ScriptCompilationConfiguration {
            jvm {
                dependenciesFromClassloader(classLoader = runtimeClassLoader, wholeClasspath = true)
            }
        }

    fun evaluationConfiguration(properties: Map<String, Any?>): ScriptEvaluationConfiguration =
        ScriptEvaluationConfiguration {
            jvm {
                baseClassLoader(runtimeClassLoader)
            }
        }

    override fun bundledScript(relativePath: String): String? {
        val normalizedPath = "${config.bundledScriptsRoot.trim('/')}/$relativePath"
        return runtimeClassLoader.getResourceAsStream(normalizedPath)?.bufferedReader()?.use { it.readText() }
    }

    override fun close() = Unit
}
