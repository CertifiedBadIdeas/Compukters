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

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.scripting.api.CompilationResult
import ru.lazyhat.compukterkraft.scripting.api.CompiledScript
import ru.lazyhat.compukterkraft.scripting.api.ScriptCompiler
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.experimental.api.CompiledScript as KotlinCompiledScript

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
        val compilation =
            runBlocking {
                compiler(StringScriptSource(code, name), environment.compilationConfiguration(name))
            }
        val diagnostics = compilation.sharedDiagnostics()

        return when (compilation) {
            is ResultWithDiagnostics.Success<KotlinCompiledScript> -> {
                CompilationResult(
                    value =
                        CompiledScriptImpl(
                            name = name,
                            code = code,
                            compiledScript = compilation.value,
                            evaluator = evaluator,
                            environment = environment,
                        ),
                    diagnostics = diagnostics,
                )
            }

            is ResultWithDiagnostics.Failure -> {
                CompilationResult(
                    diagnostics = diagnostics,
                    exceptionMessage = diagnostics.firstOrNull()?.message ?: "Compilation failed",
                )
            }
        }
    }
}
