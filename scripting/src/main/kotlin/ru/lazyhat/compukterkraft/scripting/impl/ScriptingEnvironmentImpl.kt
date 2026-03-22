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
import ru.lazyhat.compukterkraft.scripting.api.ScriptPropertyDescriptor
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironment
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import java.io.File
import java.net.URI
import kotlin.reflect.full.IllegalCallableAccessException
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.templates.standard.ScriptTemplateWithArgs

class ScriptingEnvironmentImpl(
    override val config: ScriptingEnvironmentConfig,
) : ScriptingEnvironment {
    override val definitions: List<ScriptDefinitionDescriptor> =
        config.definitions.ifEmpty { listOf(ScriptDefinitionPresets.standardKts(config.modId)) }

    val hostConfiguration = ScriptingHostConfiguration {}
    private val runtimeClassLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: javaClass.classLoader

    init {
        ensureKotlinRuntimeProperties()
    }

    val defaultImports: List<String>
        get() = definitions.flatMap { it.defaultImports }.distinct()

    override val compiler: ScriptCompiler = ScriptCompilerImpl(this)
    override val ide: ScriptIdeService = ScriptIdeServiceImpl(this)
    override val isAvailable: Boolean = true

    fun compilationConfiguration(scriptName: String): ScriptCompilationConfiguration =
        ScriptCompilationConfiguration {
            ScriptCompilationConfiguration.defaultImports(*this@ScriptingEnvironmentImpl.defaultImports.toTypedArray())
            ScriptCompilationConfiguration.providedProperties(*configuredProvidedProperties().toTypedArray())
            jvm {
                dependenciesFromClassloader(classLoader = runtimeClassLoader, wholeClasspath = true)
            }
        }

    fun evaluationConfiguration(properties: Map<String, Any?>): ScriptEvaluationConfiguration =
        ScriptEvaluationConfiguration {
            providedProperties(properties)
            jvm {
                baseClassLoader(runtimeClassLoader)
            }
        }

    private fun configuredProvidedProperties(): List<Pair<String, KotlinType>> =
        definitions
            .flatMap { it.providedProperties }
            .associateBy(ScriptPropertyDescriptor::name)
            .values
            .map { descriptor ->
                val propertyClass =
                    withRuntimeClassLoader {
                        runtimeClassLoader.loadClass(descriptor.typeName).kotlin
                    }
                descriptor.name to KotlinType(propertyClass)
            }

    override fun bundledScript(relativePath: String): String? {
        val normalizedPath = "${config.bundledScriptsRoot.trim('/')}/$relativePath"
        return runtimeClassLoader.getResourceAsStream(normalizedPath)?.bufferedReader()?.use { it.readText() }
    }

    internal fun <T> withRuntimeClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previousClassLoader = thread.contextClassLoader
        thread.contextClassLoader = runtimeClassLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previousClassLoader
        }
    }

    private fun ensureKotlinRuntimeProperties() {
        ensureRuntimeProperty(
            propertyName = KOTLIN_STDLIB_JAR_PROPERTY,
            markerClass = JvmStatic::class.java,
        )
        ensureRuntimeProperty(
            propertyName = KOTLIN_SCRIPT_RUNTIME_JAR_PROPERTY,
            markerClass = ScriptTemplateWithArgs::class.java,
        )
        ensureRuntimeProperty(
            propertyName = KOTLIN_REFLECT_JAR_PROPERTY,
            markerClass = IllegalCallableAccessException::class.java,
        )
    }

    private fun ensureRuntimeProperty(
        propertyName: String,
        markerClass: Class<*>,
    ) {
        if (!System.getProperty(propertyName).isNullOrBlank()) {
            return
        }

        locateClasspathEntry(markerClass)?.let { classpathEntry ->
            System.setProperty(propertyName, classpathEntry.absolutePath)
        }
    }

    private fun locateClasspathEntry(markerClass: Class<*>): File? =
        withRuntimeClassLoader {
            markerClass.protectionDomain
                ?.codeSource
                ?.location
                ?.toClasspathEntry()
                ?: runtimeClassLoader.resolveClasspathEntry(markerClass)
                ?: javaClass.classLoader.resolveClasspathEntry(markerClass)
        }

    private fun ClassLoader.resolveClasspathEntry(markerClass: Class<*>): File? =
        getResource("${markerClass.name.replace('.', '/')}.class")?.toClasspathEntry()

    private fun java.net.URL.toClasspathEntry(): File? =
        when (protocol) {
            "jar" -> File(URI(toExternalForm().removePrefix("jar:").substringBefore('!'))).takeIf(File::exists)
            "file" -> File(toURI()).takeIf(File::exists)
            else -> null
        }

    override fun close() = Unit

    private companion object {
        const val KOTLIN_STDLIB_JAR_PROPERTY = "kotlin.java.stdlib.jar"
        const val KOTLIN_REFLECT_JAR_PROPERTY = "kotlin.java.reflect.jar"
        const val KOTLIN_SCRIPT_RUNTIME_JAR_PROPERTY = "kotlin.script.runtime.jar"
    }
}
