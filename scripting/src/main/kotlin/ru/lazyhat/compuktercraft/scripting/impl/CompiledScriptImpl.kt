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
        val evaluation =
            runBlocking {
                evaluator(compiledScript, environment.evaluationConfiguration(properties))
            }
        val diagnostics = evaluation.sharedDiagnostics()

        return when (evaluation) {
            is ResultWithDiagnostics.Success -> {
                ScriptExecutionResult(
                    returnValue = evaluation.value.toString(),
                    diagnostics = diagnostics,
                )
            }

            is ResultWithDiagnostics.Failure -> {
                ScriptExecutionResult(
                    returnValue = code,
                    diagnostics = diagnostics,
                    exceptionMessage = diagnostics.firstOrNull()?.message ?: "Execution failed",
                )
            }
        }
    }
}
