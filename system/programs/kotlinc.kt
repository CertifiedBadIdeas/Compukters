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
import compukter.process.Process
import compukter.terminal.Terminal

suspend fun main() {
    val commandLine = Process.commandLine()
    val error = kotlincError(commandLine)
    if (error != "") {
        Terminal.write(error + "\n")
    } else {
        val source = kotlincSource(commandLine)
        val output = kotlincOutput(commandLine)
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

fun kotlincError(commandLine: String): String {
    val parsed = parseKotlincCommandLine(commandLine)
    val separator = separator(parsed, 0)
    return parsed.substring(0, separator)
}

fun kotlincSource(commandLine: String): String {
    val parsed = parseKotlincCommandLine(commandLine)
    val first = separator(parsed, 0)
    val second = separator(parsed, first + 1)
    return parsed.substring(first + 1, second)
}

fun kotlincOutput(commandLine: String): String {
    val parsed = parseKotlincCommandLine(commandLine)
    val first = separator(parsed, 0)
    val second = separator(parsed, first + 1)
    return parsed.substring(second + 1, parsed.length)
}

private fun parseKotlincCommandLine(commandLine: String): String {
    val count = tokenCount(commandLine)
    if (count == 0) return failure("usage: kotlinc <source.kt> [-o output]")

    var outputOptions = 0
    var index = 0
    while (index < count) {
        if (tokenAt(commandLine, index) == "-o") outputOptions = outputOptions + 1
        index = index + 1
    }
    if (outputOptions > 1) return failure("duplicate -o option")

    val source = tokenAt(commandLine, 0)
    if (source == "-o") return failure("source file must precede -o")
    if (!hasKotlinExtension(source)) return failure("source file must end in .kt")

    val resolvedSource = resolveUserPath(source)
    var output = ""
    if (count == 1) output = resolvedSource.substring(0, resolvedSource.length - 3)
    else if (count == 2 && tokenAt(commandLine, 1) == "-o") return failure("missing output after -o")
    else if (count == 3 && tokenAt(commandLine, 1) == "-o") output = resolveUserPath(tokenAt(commandLine, 2))
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

private fun tokenCount(commandLine: String): Int {
    var count = 0
    var index = 0
    while (index < commandLine.length) {
        while (index < commandLine.length && commandLine[index] == ' ') index = index + 1
        if (index < commandLine.length) {
            count = count + 1
            while (index < commandLine.length && commandLine[index] != ' ') index = index + 1
        }
    }
    return count
}

private fun tokenAt(
    commandLine: String,
    requested: Int,
): String {
    var token = 0
    var index = 0
    while (index < commandLine.length) {
        while (index < commandLine.length && commandLine[index] == ' ') index = index + 1
        val start = index
        while (index < commandLine.length && commandLine[index] != ' ') index = index + 1
        if (start != index) {
            if (token == requested) return commandLine.substring(start, index)
            token = token + 1
        }
    }
    return ""
}

private fun hasKotlinExtension(path: String): Boolean =
    path.length > 3 && path.substring(path.length - 3, path.length) == ".kt"

private fun resolveUserPath(path: String): String = if (path[0] == '/') path else "/home/" + path
