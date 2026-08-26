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

package compukter.io

object Stderr {
    fun write(payload: String): Unit = Unit
}

private object StdioBindings {
    fun write(payload: String): Unit = Unit
}

private fun stdoutPrintString(value: String): Unit = StdioBindings.write(value)

private fun stdoutPrintlnString(value: String): Unit = StdioBindings.write(value + "\n")

private fun stdoutPrintInt(value: Int): Unit = StdioBindings.write(stdoutInt(value))

private fun stdoutPrintlnInt(value: Int): Unit = StdioBindings.write(stdoutInt(value) + "\n")

private fun stdoutPrintBoolean(value: Boolean): Unit = StdioBindings.write(if (value) "true" else "false")

private fun stdoutPrintlnBoolean(value: Boolean): Unit = StdioBindings.write(if (value) "true\n" else "false\n")

private fun stdoutPrintChar(value: Char): Unit = StdioBindings.write(stdoutChar(value))

private fun stdoutPrintlnChar(value: Char): Unit = StdioBindings.write(stdoutChar(value) + "\n")

private fun stdoutPrintln(): Unit = StdioBindings.write("\n")

private fun stdoutChar(value: Char): String {
    val contents = CharArray(1)
    contents[0] = value
    return String(contents, 0, 1)
}

private fun stdoutInt(value: Int): String {
    if (value == 0) return "0"
    val contents = CharArray(11)
    var cursor = 11
    var remaining = if (value > 0) 0 - value else value
    while (remaining != 0) {
        val digit = 0 - (remaining % 10)
        cursor = cursor - 1
        contents[cursor] = (48 + digit).toChar()
        remaining = remaining / 10
    }
    if (value < 0) {
        cursor = cursor - 1
        contents[cursor] = '-'
    }
    return String(contents, cursor, 11 - cursor)
}
