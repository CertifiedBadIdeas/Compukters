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
