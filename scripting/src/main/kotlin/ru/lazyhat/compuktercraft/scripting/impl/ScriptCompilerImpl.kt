package ru.lazyhat.compuktercraft.scripting.impl

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compuktercraft.scripting.api.CompiledScript
import ru.lazyhat.compuktercraft.scripting.api.CompilationResult
import ru.lazyhat.compuktercraft.scripting.api.ScriptCompiler
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.CompiledScript as KotlinCompiledScript
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvmhost.JvmScriptCompiler

class ScriptCompilerImpl(
    private val environment: ScriptingEnvironmentImpl,
) : ScriptCompiler {
    private val compiler by lazy(LazyThreadSafetyMode.NONE) {
        JvmScriptCompiler(environment.hostConfiguration)
    }
    private val evaluator = BasicJvmScriptEvaluator()

    override fun compile(
        name: String,
        code: String,
    ): CompilationResult<CompiledScript> {
        val compilation = runBlocking {
            compiler(StringScriptSource(code, name), environment.compilationConfiguration(name))
        }
        val diagnostics = compilation.sharedDiagnostics()

        return when (compilation) {
            is ResultWithDiagnostics.Success<KotlinCompiledScript> ->
                CompilationResult(
                    value = CompiledScriptImpl(
                        name = name,
                        code = code,
                        compiledScript = compilation.value,
                        evaluator = evaluator,
                        environment = environment,
                    ),
                    diagnostics = diagnostics,
                )

            is ResultWithDiagnostics.Failure ->
                CompilationResult(
                    diagnostics = diagnostics,
                    exceptionMessage = diagnostics.firstOrNull()?.message ?: "Compilation failed",
                )
        }
    }
}
