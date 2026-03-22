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

package ru.lazyhat.compukterkraft.scripting.runtime

import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironment
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentConfig
import ru.lazyhat.compukterkraft.scripting.api.ScriptingEnvironmentInitializer
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLClassLoader

class ScriptingJarLoader(
    private val scriptingJar: File = ScriptingPaths.compilerJar(),
    private val librariesDirectory: File = scriptingJar.absoluteFile.parentFile.resolve(ScriptingPaths.SCRIPTING_LIBRARIES_DIRECTORY),
    private val implementationClassName: String = "ru.lazyhat.compukterkraft.scripting.impl.ScriptingEnvironmentInitializerImpl",
) : AutoCloseable {
    var isLoaded = false
        private set

    var lastError: String? = null
        private set

    private var classLoader: URLClassLoader? = null

    fun hasScriptingJar(): Boolean = scriptingJar.exists()

    private fun runtimeUrls() =
        buildList {
            add(scriptingJar.toURI().toURL())
            librariesDirectory
                .takeIf(File::isDirectory)
                ?.listFiles { file -> file.isFile && file.name.endsWith(".jar") }
                ?.sortedBy { file -> file.name }
                ?.forEach { add(it.toURI().toURL()) }
        }.toTypedArray()

    fun initialize(config: ScriptingEnvironmentConfig): ScriptingEnvironment? {
        if (!hasScriptingJar()) {
            lastError = "Scripting jar not found at ${scriptingJar.absolutePath}"
            return null
        }

        ensureKotlinRuntimeProperties()

        val currentThread = Thread.currentThread()
        val previousContextLoader = currentThread.contextClassLoader
        val parentLoader = ScriptingEnvironmentInitializer::class.java.classLoader

        return try {
            classLoader = ScriptingRuntimeClassLoader(runtimeUrls(), parentLoader)
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

    private fun ensureKotlinRuntimeProperties() {
        ensureRuntimeProperty(
            propertyName = KOTLIN_STDLIB_JAR_PROPERTY,
            markerClass = JvmStatic::class.java,
            jarPrefix = KOTLIN_STDLIB_JAR_PREFIX,
        )
    }

    private fun ensureRuntimeProperty(
        propertyName: String,
        markerClass: Class<*>,
        jarPrefix: String,
    ) {
        if (!System.getProperty(propertyName).isNullOrBlank()) {
            return
        }

        resolveRuntimeJar(markerClass, jarPrefix)
            ?.let { System.setProperty(propertyName, it.absolutePath) }
    }

    private fun resolveRuntimeJar(
        markerClass: Class<*>,
        jarPrefix: String,
    ): File? =
        markerClass.protectionDomain
            ?.codeSource
            ?.location
            ?.toClasspathEntry()
            ?.takeIf { it.isMatchingJar(jarPrefix) }
            ?: markerClass.classLoader
                ?.getResource("${markerClass.name.replace('.', '/')}.class")
                ?.toClasspathEntry()
                ?.takeIf { it.isMatchingJar(jarPrefix) }
            ?: findJarInJavaClassPath(System.getProperty(JAVA_CLASS_PATH_PROPERTY), jarPrefix)

    private fun URL.toClasspathEntry(): File? =
        when (protocol) {
            "jar" -> File(URI(toExternalForm().removePrefix("jar:").substringBefore('!'))).takeIf(File::exists)
            "file" -> File(toURI()).takeIf(File::exists)
            else -> null
        }

    private fun File.isMatchingJar(jarPrefix: String): Boolean = isFile && (name == "$jarPrefix.jar" || name.startsWith("$jarPrefix-"))

    private companion object {
        const val JAVA_CLASS_PATH_PROPERTY = "java.class.path"
        const val KOTLIN_STDLIB_JAR_PROPERTY = "kotlin.java.stdlib.jar"
        const val KOTLIN_STDLIB_JAR_PREFIX = "kotlin-stdlib-jdk8"
    }
}

internal fun findJarInJavaClassPath(
    classPath: String?,
    jarPrefix: String,
): File? =
    classPath
        ?.split(File.pathSeparatorChar)
        ?.asSequence()
        ?.map(::File)
        ?.firstOrNull { file ->
            file.isFile &&
                file.name.endsWith(".jar") &&
                (file.name == "$jarPrefix.jar" || file.name.startsWith("$jarPrefix-"))
        }

private class ScriptingRuntimeClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    private val parentFirstPrefixes =
        listOf(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "kotlin.coroutines.",
            "kotlin.jvm.functions.",
            "kotlinx.coroutines.",
            "ru.lazyhat.compukterkraft.machine.",
            "ru.lazyhat.compukterkraft.scripting.api.",
        )

    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { loadedClass ->
                if (resolve) {
                    resolveClass(loadedClass)
                }
                return loadedClass
            }

            val loadedClass =
                if (parentFirstPrefixes.any(name::startsWith)) {
                    super.loadClass(name, resolve)
                } else {
                    try {
                        findClass(name)
                    } catch (_: ClassNotFoundException) {
                        super.loadClass(name, resolve)
                    }
                }

            if (resolve) {
                resolveClass(loadedClass)
            }

            loadedClass
        }
}
