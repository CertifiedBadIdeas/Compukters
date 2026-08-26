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

@file:Suppress("UNUSED_PARAMETER")

package compukter.process

object Process {
    fun run(
        path: String,
        args: Array<String> = emptyArray(),
    ): ProcessResult {
        val raw = ProcessBindings.run(path, encodeArgs(args))
        if (raw >= 0) return ProcessResult.Exited(raw)
        return ProcessResult.Failed(
            failureReason(-raw),
            ProcessBindings.takeFailureDiagnostic(),
        )
    }

    fun exit(code: Int): Nothing = ProcessBindings.exit(code)
}

sealed interface ProcessResult {
    data class Exited(
        val code: Int,
    ) : ProcessResult

    data class Failed(
        val reason: ProcessFailureReason,
        val diagnostic: String,
    ) : ProcessResult
}

enum class ProcessFailureReason {
    INVALID_PATH,
    NOT_FOUND,
    ACCESS_DENIED,
    NOT_EXECUTABLE,
    INVALID_PROGRAM,
    INCOMPATIBLE,
    LIMIT_EXCEEDED,
    TRAPPED,
    VM_FAULT,
    HOST_FAILURE,
    IO_FAILURE,
}

private object ProcessBindings {
    fun run(
        path: String,
        encodedArgs: String,
    ): Int = 0

    fun takeFailureDiagnostic(): String = ""

    fun exit(code: Int): Nothing = throw IllegalStateException("intrinsic")
}

private fun failureReason(code: Int): ProcessFailureReason =
    when (code) {
        1 -> ProcessFailureReason.INVALID_PATH
        2 -> ProcessFailureReason.NOT_FOUND
        3 -> ProcessFailureReason.ACCESS_DENIED
        4 -> ProcessFailureReason.NOT_EXECUTABLE
        5 -> ProcessFailureReason.INVALID_PROGRAM
        6 -> ProcessFailureReason.INCOMPATIBLE
        7 -> ProcessFailureReason.LIMIT_EXCEEDED
        8 -> ProcessFailureReason.TRAPPED
        9 -> ProcessFailureReason.VM_FAULT
        10 -> ProcessFailureReason.HOST_FAILURE
        11 -> ProcessFailureReason.IO_FAILURE
        else -> ProcessBindings.exit(-1)
    }

private fun encodeArgs(args: Array<String>): String {
    var size = 2
    var index = 0
    while (index < args.size) {
        val length = args[index].length
        if (size > Int.MAX_VALUE - 2 - length) ProcessBindings.exit(-1)
        size = size + 2 + length
        index = index + 1
    }

    val encoded = CharArray(size)
    encoded[0] = (args.size % 65536).toChar()
    encoded[1] = (args.size / 65536).toChar()
    var output = 2
    index = 0
    while (index < args.size) {
        val argument = args[index]
        encoded[output] = (argument.length % 65536).toChar()
        encoded[output + 1] = (argument.length / 65536).toChar()
        output = output + 2
        var input = 0
        while (input < argument.length) {
            encoded[output] = argument[input]
            output = output + 1
            input = input + 1
        }
        index = index + 1
    }
    return String(encoded, 0, encoded.size)
}
