package ru.lazyhat.compuktercraft.scripting.impl

import ru.lazyhat.compuktercraft.scripting.api.ScriptDefinitionPresets
import ru.lazyhat.compuktercraft.scripting.api.ScriptDefinitionDescriptor
import ru.lazyhat.compuktercraft.scripting.api.ScriptIdeService
import ru.lazyhat.compuktercraft.scripting.api.ScriptCompiler
import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironment
import ru.lazyhat.compuktercraft.scripting.api.ScriptingEnvironmentConfig
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
    val runtimeClassLoader: ClassLoader
        get() = Thread.currentThread().contextClassLoader ?: javaClass.classLoader

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
