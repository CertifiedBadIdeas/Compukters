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

import compukter.compiler.Compiler
import compukter.terminal.Terminal

suspend fun main(args: Array<String>) {
    val error = kotlincError(args)
    if (error != "") {
        Terminal.write(error + "\n")
    } else {
        val source = kotlincSource(args)
        val output = kotlincOutput(args)
        val result = Compiler.compile(source, output)
        if (result == 0) {
            Terminal.write("compiled: " + output + "\n")
        } else {
            val diagnostics = Compiler.diagnostics()
            if (diagnostics != "") Terminal.write(diagnostics + "\n")
            else Terminal.write("compilation failed\n")
        }
    }
}

fun kotlincError(args: Array<String>): String {
    val parsed = parseKotlincArguments(args)
    val separator = separator(parsed, 0)
    return parsed.substring(0, separator)
}

fun kotlincSource(args: Array<String>): String {
    val parsed = parseKotlincArguments(args)
    val first = separator(parsed, 0)
    val second = separator(parsed, first + 1)
    return parsed.substring(first + 1, second)
}

fun kotlincOutput(args: Array<String>): String {
    val parsed = parseKotlincArguments(args)
    val first = separator(parsed, 0)
    val second = separator(parsed, first + 1)
    return parsed.substring(second + 1, parsed.length)
}

private fun parseKotlincArguments(args: Array<String>): String {
    val count = args.size
    if (count == 0) return failure("usage: kotlinc <source.kt> [-o output]")

    var outputOptions = 0
    var index = 0
    while (index < count) {
        if (args[index] == "-o") outputOptions = outputOptions + 1
        index = index + 1
    }
    if (outputOptions > 1) return failure("duplicate -o option")

    val source = args[0]
    if (source == "-o") return failure("source file must precede -o")
    if (!hasKotlinExtension(source)) return failure("source file must end in .kt")

    val resolvedSource = resolveUserPath(source)
    var output = ""
    if (count == 1) output = resolvedSource.substring(0, resolvedSource.length - 3)
    else if (count == 2 && args[1] == "-o") return failure("missing output after -o")
    else if (count == 3 && args[1] == "-o") output = resolveUserPath(args[2])
    else return failure("kotlinc accepts exactly one source file")
    return "\u0000" + resolvedSource + "\u0000" + output
}

private fun failure(message: String): String = message + "\u0000\u0000"

private fun separator(
    value: String,
    start: Int,
): Int {
    var index = start
    while (index < value.length && value[index] != '\u0000') index = index + 1
    return index
}

private fun hasKotlinExtension(path: String): Boolean =
    path.length > 3 && path.substring(path.length - 3, path.length) == ".kt"

private fun resolveUserPath(path: String): String = if (path[0] == '/') path else "/home/" + path
