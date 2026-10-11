/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package compukter.system.kotlinc

import compukter.compiler.CompilationResult
import compukter.compiler.Compiler
import compukter.filesystem.Path
import compukter.io.Stderr

fun main(args: Array<String>) {
    when (val arguments = parseKotlincArguments(args)) {
        is KotlincArguments.Error -> {
            Stderr.write(arguments.message + "\n")
        }

        is KotlincArguments.Source -> {
            when (val result = Compiler.compile(arguments.path, arguments.output)) {
                is CompilationResult.Success -> {
                    println("compiled: " + result.output.value)
                }

                is CompilationResult.Failed -> {
                    if (result.diagnostics.isNotEmpty()) {
                        Stderr.write(result.diagnostics + "\n")
                    } else {
                        Stderr.write("compilation failed\n")
                    }
                }
            }
        }
    }
}

sealed interface KotlincArguments {
    data class Source(
        val path: Path,
        val output: Path,
    ) : KotlincArguments

    data class Error(
        val message: String,
    ) : KotlincArguments
}

fun parseKotlincArguments(args: Array<String>): KotlincArguments {
    val count = args.size
    if (count == 0) return KotlincArguments.Error("usage: kotlinc <source.kt> [-o output]")

    var outputOptions = 0
    for (index in 0 until count) {
        if (args[index] == "-o") outputOptions = outputOptions + 1
    }
    if (outputOptions > 1) return KotlincArguments.Error("duplicate -o option")

    val source = args[0]
    if (source == "-o") return KotlincArguments.Error("source file must precede -o")
    if (source.length <= 3 || !source.endsWith(".kt")) return KotlincArguments.Error("source file must end in .kt")

    val resolvedSource = resolveUserPath(source)
    val output =
        when {
            count == 1 -> resolvedSource.removeSuffix(".kt")
            count == 2 && args[1] == "-o" -> return KotlincArguments.Error("missing output after -o")
            count == 3 && args[1] == "-o" -> resolveUserPath(args[2])
            else -> return KotlincArguments.Error("kotlinc accepts exactly one source file")
        }
    return try {
        KotlincArguments.Source(Path(resolvedSource), Path(output))
    } catch (failure: IllegalArgumentException) {
        KotlincArguments.Error("invalid compilation path")
    }
}

private fun resolveUserPath(path: String): String = if (path.startsWith("/")) path else "/home/" + path
