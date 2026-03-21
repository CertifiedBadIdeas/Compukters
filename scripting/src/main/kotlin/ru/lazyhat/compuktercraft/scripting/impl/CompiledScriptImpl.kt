package ru.lazyhat.compuktercraft.scripting.impl

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compuktercraft.scripting.api.CompiledScript
import ru.lazyhat.compuktercraft.scripting.api.ScriptExecutionResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.api.CompiledScript as KotlinCompiledScript

class CompiledScriptImpl(
    override val name: String,
    private val code: String,
    private val compiledScript: KotlinCompiledScript,
    private val evaluator: BasicJvmScriptEvaluator,
    private val environment: ScriptingEnvironmentImpl,
) : CompiledScript {
    override fun execute(properties: Map<String, Any?>): ScriptExecutionResult {
        val evaluation = runBlocking {
            evaluator(compiledScript, environment.evaluationConfiguration(properties))
        }
        val diagnostics = evaluation.sharedDiagnostics()

        return when (evaluation) {
            is ResultWithDiagnostics.Success ->
                ScriptExecutionResult(
                    returnValue = evaluation.value.toString(),
                    diagnostics = diagnostics,
                )

            is ResultWithDiagnostics.Failure ->
                ScriptExecutionResult(
                    returnValue = code,
                    diagnostics = diagnostics,
                    exceptionMessage = diagnostics.firstOrNull()?.message ?: "Execution failed",
                )
        }
    }
}
