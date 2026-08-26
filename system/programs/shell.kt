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

package compukter.system.shell

import compukter.filesystem.FileSystem
import compukter.process.Process
import compukter.process.ProcessFailureReason
import compukter.process.ProcessResult
import compukter.terminal.Terminal

fun main() {
    var line = ""
    Terminal.write("> ")
    while (true) {
        val event = Terminal.awaitEvent()
        if (event == 1) {
            val text = Terminal.eventText()
            Terminal.finishEvent()
            line = appendText(line, text)
        } else if (event == 2) {
            val key = Terminal.eventKey()
            val action = Terminal.eventAction()
            Terminal.finishEvent()
            if (key == 13 && action == 1) {
                Terminal.write("\n")
                if (line != "") {
                    when {
                        line == "help" -> Terminal.write("help echo clear pwd ls stat kotlinc edit\n")
                        line == "echo" -> Terminal.write("\n")
                        line.length >= 5 && line.substring(0, 5) == "echo " -> {
                            Terminal.write(line.substring(5, line.length) + "\n")
                        }

                        line == "clear" -> Terminal.clear()
                        line == "pwd" -> Terminal.write("/home\n")
                        line == "ls" -> writeList("/home")
                        line.length >= 3 && line.substring(0, 3) == "ls " -> {
                            val argument = line.substring(3, line.length)
                            if (argument == "" || containsSpace(argument)) {
                                Terminal.write("usage: ls [path]\n")
                            } else {
                                writeList(resolvePath(argument))
                            }
                        }

                        line == "stat" -> Terminal.write("usage: stat <path>\n")
                        line.length >= 5 && line.substring(0, 5) == "stat " -> {
                            val argument = line.substring(5, line.length)
                            if (argument == "" || containsSpace(argument)) {
                                Terminal.write("usage: stat <path>\n")
                            } else {
                                writeStat(resolvePath(argument))
                            }
                        }

                        else -> {
                            val argumentError = shellArgumentError(line)
                            if (argumentError != "") {
                                Terminal.write(argumentError + "\n")
                            } else {
                                val command = shellCommand(line)
                                val arguments = shellArguments(line)
                                val absolute = command[0] == '/'
                                var path = if (absolute) command else "/home/" + command
                                var result = Process.run(path, arguments)
                                if (!absolute) {
                                    if (result is ProcessResult.Failed && result.reason == ProcessFailureReason.NOT_FOUND) {
                                        path = "/rom/" + command
                                        result = Process.run(path, arguments)
                                    }
                                }
                                writeProcessResult(result, path)
                            }
                        }
                    }
                }
                line = ""
                Terminal.write("> ")
            } else if (
                key == 8 &&
                (action == 1 || action == 2) &&
                line.length > 0
            ) {
                line = eraseLastScalar(line)
                Terminal.erasePrevious()
            }
        }
    }
}

fun shellCommand(line: String): String {
    var end = 0
    while (end < line.length && line[end] != ' ') end = end + 1
    return line.substring(0, end)
}

fun shellArguments(line: String): Array<String> {
    val words = arrayOf("", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "")
    var count = 0
    var index = 0
    while (index < line.length) {
        while (index < line.length && line[index] == ' ') index = index + 1
        val start = index
        while (index < line.length && line[index] != ' ') index = index + 1
        if (start != index) {
            if (count > 0 && count <= words.size) words[count - 1] = line.substring(start, index)
            count = count + 1
        }
    }
    if (count == 0) return emptyArray()
    val argumentCount = if (count > words.size + 1) words.size else count - 1
    return words.copyOfRange(0, argumentCount)
}

fun shellArgumentError(line: String): String =
    if (shellWordCount(line) > 17) "too many arguments (maximum 16)" else ""

private fun shellWordCount(line: String): Int {
    var count = 0
    var index = 0
    while (index < line.length) {
        while (index < line.length && line[index] == ' ') index = index + 1
        val start = index
        while (index < line.length && line[index] != ' ') index = index + 1
        if (start != index) count = count + 1
    }
    return count
}

private fun containsSpace(value: String): Boolean {
    var index = 0
    var found = false
    while (index < value.length && !found) {
        if (value[index] == ' ') found = true
        index = index + 1
    }
    return found
}

private fun resolvePath(path: String): String {
    return if (path[0] == '/') path else "/home/" + path
}

private fun writeList(path: String) {
    val kind = FileSystem.stat(path)
    if (kind == 1) {
        Terminal.write(path + "\n")
    } else if (kind == 2) {
        val names = FileSystem.list(path)
        var start = 0
        while (start < names.length) {
            var end = start
            while (end < names.length && names[end] != '\u0000') end = end + 1
            if (end != start) Terminal.write(names.substring(start, end) + "\n")
            start = end + 1
        }
    } else {
        writeFileSystemFailure(kind, path)
    }
}

private fun writeStat(path: String) {
    val kind = FileSystem.stat(path)
    if (kind == 1) Terminal.write("file: " + path + "\n")
    else if (kind == 2) Terminal.write("directory: " + path + "\n")
    else writeFileSystemFailure(kind, path)
}

private fun writeFileSystemFailure(
    result: Int,
    path: String,
) {
    if (result == -1) Terminal.write("invalid path: " + path)
    else if (result == -2) Terminal.write("not found: " + path)
    else if (result == -3) Terminal.write("already exists: " + path)
    else if (result == -4) Terminal.write("not a directory: " + path)
    else if (result == -5) Terminal.write("is a directory: " + path)
    else if (result == -6) Terminal.write("directory not empty: " + path)
    else if (result == -7) Terminal.write("read-only filesystem: " + path)
    else if (result == -8) Terminal.write("permission denied: " + path)
    else if (result == -9) Terminal.write("stale file handle: " + path)
    else if (result == -10) Terminal.write("filesystem quota exceeded")
    else if (result == -11) Terminal.write("filesystem busy")
    else if (result == -12) Terminal.write("filesystem unavailable")
    else if (result == -13) Terminal.write("filesystem closed")
    else if (result == -14) Terminal.write("not executable: " + path)
    else Terminal.write("filesystem error")
    Terminal.write("\n")
}

private fun appendText(
    current: String,
    text: String,
): String {
    var result = current
    var index = 0
    while (index < text.length) {
        val character = text[index]
        var width = 1
        if (character >= '\uD800' && character <= '\uDBFF' && index + 1 < text.length) {
            val next = text[index + 1]
            if (next >= '\uDC00' && next <= '\uDFFF') width = 2
        }
        if (character >= ' ' && character != '\u007f' && result.length + width <= 256) {
            val accepted = text.substring(index, index + width)
            result = result + accepted
            Terminal.write(accepted)
        }
        index = index + width
    }
    return result
}

private fun eraseLastScalar(line: String): String {
    var width = 1
    val last = line[line.length - 1]
    if (last >= '\uDC00' && last <= '\uDFFF' && line.length >= 2) {
        val previous = line[line.length - 2]
        if (previous >= '\uD800' && previous <= '\uDBFF') width = 2
    }
    return line.substring(0, line.length - width)
}

private fun writeProcessResult(
    result: ProcessResult,
    path: String,
) {
    if (result is ProcessResult.Exited) {
        if (result.code == 0) return
        Terminal.write(path + ": exited with an error\n")
        return
    }
    if (result is ProcessResult.Failed) {
        if (result.diagnostic != "") {
            Terminal.write(result.diagnostic)
            Terminal.write("\n")
            return
        }
        when (result.reason) {
            ProcessFailureReason.INVALID_PATH -> Terminal.write("invalid path: " + path)
            ProcessFailureReason.NOT_FOUND -> Terminal.write("command not found: " + path)
            ProcessFailureReason.ACCESS_DENIED -> Terminal.write("permission denied: " + path)
            ProcessFailureReason.NOT_EXECUTABLE -> Terminal.write("not executable: " + path)
            ProcessFailureReason.INVALID_PROGRAM -> Terminal.write("invalid executable: " + path)
            ProcessFailureReason.INCOMPATIBLE -> Terminal.write("incompatible program: " + path)
            ProcessFailureReason.LIMIT_EXCEEDED -> Terminal.write("process limit exceeded")
            ProcessFailureReason.TRAPPED -> Terminal.write("program trapped: " + path)
            ProcessFailureReason.VM_FAULT -> Terminal.write("virtual machine fault")
            ProcessFailureReason.HOST_FAILURE -> Terminal.write("host capability failed")
            ProcessFailureReason.IO_FAILURE -> Terminal.write("I/O error: " + path)
        }
        Terminal.write("\n")
    }
}
