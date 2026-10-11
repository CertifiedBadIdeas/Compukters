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

import compukter.filesystem.FileKind
import compukter.filesystem.FileSystem
import compukter.filesystem.FileSystemException
import compukter.filesystem.Path
import compukter.io.Stderr
import compukter.io.use
import compukter.process.Process
import compukter.process.ProcessFailureReason
import compukter.process.ProcessResult
import compukter.terminal.Terminal

fun main() {
    while (true) {
        print("> ")
        dispatch(readln())
    }
}

private fun dispatch(line: String) {
    when (val result = lex(line)) {
        is LexResult.Error -> Stderr.write(result.message + "\n")
        is LexResult.Success -> dispatchWords(result.words)
    }
}

private fun dispatchWords(words: Array<String>) {
    if (words.size == 0) return
    val command = words[0]
    when (command) {
        "help" -> {
            writeHelp(words)
        }

        "echo" -> {
            for (index in 1 until words.size) {
                if (index > 1) print(" ")
                print(words[index])
            }
            println()
        }

        "clear" -> {
            if (words.size == 1) Terminal.clear() else Stderr.write("usage: clear\n")
        }

        "pwd" -> {
            if (words.size == 1) println("/home") else Stderr.write("usage: pwd\n")
        }

        "ls" -> {
            if (words.size == 1) {
                writeList("/home")
            } else if (words.size == 2 && words[1].isNotEmpty()) {
                writeList(resolvePath(words[1]))
            } else {
                Stderr.write("usage: ls [path]\n")
            }
        }

        "stat" -> {
            if (words.size == 2 && words[1].isNotEmpty()) {
                writeStat(resolvePath(words[1]))
            } else {
                Stderr.write("usage: stat <path>\n")
            }
        }

        "cat", "mkdir", "mv", "rm" -> {
            executeFileCommand(words)
        }

        "" -> {
            Stderr.write("empty command\n")
        }

        else -> {
            executeExternal(words)
        }
    }
}

private fun executeExternal(words: Array<String>) {
    val command = words[0]
    val arguments = words.copyOfRange(1, words.size)
    var path =
        try {
            Path(resolvePath(command))
        } catch (failure: IllegalArgumentException) {
            Stderr.write("invalid path: $command\n")
            return
        }
    var result = Process.run(path, arguments)
    val fallback = shellFallbackPath(command, result)
    if (fallback.isNotEmpty()) {
        path = Path(fallback)
        result = Process.run(path, arguments)
    }
    val diagnostic = shellProcessDiagnostic(result, path.normalize().value)
    if (diagnostic.isNotEmpty()) Stderr.write(diagnostic + "\n")
}

fun shellFallbackPath(
    command: String,
    result: ProcessResult,
): String {
    if (command.isEmpty() || command.startsWith("/")) return ""
    if (result is ProcessResult.Failed && result.reason == ProcessFailureReason.NOT_FOUND) {
        return "/rom/" + command
    }
    return ""
}

private fun resolvePath(path: String): String = if (path.startsWith("/")) path else "/home/" + path

private fun writeHelp(words: Array<String>) {
    if (words.size == 1) {
        println("help echo clear pwd ls stat cat mkdir mv rm")
        println("kotlinc edit vmbench")
        println("help <command> for usage")
        println("Relative paths start at /home; quote spaces.")
        return
    }
    if (words.size != 2) {
        Stderr.write("usage: help [command]\n")
        return
    }
    val usage = commandUsage(words[1])
    if (usage.isEmpty()) {
        Stderr.write("no help for command: ${words[1]}\n")
    } else {
        println(usage)
    }
}

private fun commandUsage(command: String): String =
    when (command) {
        "help" -> "help [command]"
        "echo" -> "echo [words...]"
        "clear" -> "clear"
        "pwd" -> "pwd"
        "ls" -> "ls [path]"
        "stat" -> "stat <path>"
        "cat" -> "cat <path> (UTF-8, at most 65536 bytes)"
        "mkdir" -> "mkdir [-p] <path>"
        "mv" -> "mv <source> <destination> (no overwrite)"
        "rm" -> "rm <path> (file or empty directory)"
        "kotlinc" -> "kotlinc <source.kt> [-o output]"
        "edit" -> "edit <path>"
        "vmbench" -> "vmbench <cpu|redstone> <rounds 1..1000000>"
        else -> ""
    }

private fun executeFileCommand(words: Array<String>) {
    val command = words[0]
    val parents = command == "mkdir" && words.size > 1 && words[1] == "-p"
    val required = if (command == "mv" || parents) 3 else 2
    if (words.size != required || words[1].isEmpty() || (required == 3 && words[2].isEmpty())) {
        Stderr.write("usage: ${commandUsage(command)}\n")
        return
    }
    try {
        val path = Path(resolvePath(if (parents) words[2] else words[1]))
        when (command) {
            "cat" -> {
                val text = FileSystem.readText(path, 65536)
                var start = 0
                while (start < text.length) {
                    var end = if (text.length - start > 256) start + 256 else text.length
                    // Keep each stdout request bounded without splitting a Unicode scalar.
                    if (end < text.length && text[end - 1].code in 55296..56319) end--
                    print(text.substring(start, end))
                    start = end
                }
            }

            "mkdir" -> {
                if (parents) FileSystem.createDirectories(path) else FileSystem.createDirectory(path)
            }

            "mv" -> {
                FileSystem.move(path, Path(resolvePath(words[2])))
            }

            "rm" -> {
                FileSystem.delete(path)
            }
        }
    } catch (failure: FileSystemException) {
        Stderr.write("$command: ${failure.message ?: "filesystem error"}\n")
    } catch (failure: IllegalArgumentException) {
        Stderr.write("$command: invalid path\n")
    }
}

private fun writeList(path: String) {
    try {
        val entry = Path(path)
        if (FileSystem.stat(entry).kind == FileKind.FILE) {
            println(path)
        } else {
            FileSystem.openDirectory(entry).use { directory ->
                while (true) {
                    val child = directory.read() ?: break
                    println(child.name ?: "")
                }
            }
        }
    } catch (failure: FileSystemException) {
        Stderr.write((failure.message ?: "filesystem error") + "\n")
    } catch (failure: IllegalArgumentException) {
        Stderr.write("invalid path: $path\n")
    }
}

private fun writeStat(path: String) {
    try {
        val info = FileSystem.stat(Path(path))
        println(if (info.kind == FileKind.FILE) "file: $path" else "directory: $path")
    } catch (failure: FileSystemException) {
        Stderr.write((failure.message ?: "filesystem error") + "\n")
    } catch (failure: IllegalArgumentException) {
        Stderr.write("invalid path: $path\n")
    }
}

fun shellProcessDiagnostic(
    result: ProcessResult,
    path: String,
): String {
    if (result is ProcessResult.Exited) {
        if (result.code == 0) return ""
        return path + ": exited with an error"
    }
    if (result is ProcessResult.Failed) {
        if (result.diagnostic != "") return result.diagnostic
        if (result.reason == ProcessFailureReason.INVALID_PATH) return "invalid path: " + path
        if (result.reason == ProcessFailureReason.NOT_FOUND) return "command not found: " + path
        if (result.reason == ProcessFailureReason.ACCESS_DENIED) return "permission denied: " + path
        if (result.reason == ProcessFailureReason.NOT_EXECUTABLE) return "not executable: " + path
        if (result.reason == ProcessFailureReason.INVALID_PROGRAM) return "invalid executable: " + path
        if (result.reason == ProcessFailureReason.INCOMPATIBLE) return "incompatible program: " + path
        if (result.reason == ProcessFailureReason.LIMIT_EXCEEDED) return "process limit exceeded"
        if (result.reason == ProcessFailureReason.TRAPPED) return "program trapped: " + path
        if (result.reason == ProcessFailureReason.VM_FAULT) return "virtual machine fault"
        if (result.reason == ProcessFailureReason.HOST_FAILURE) return "host capability failed"
        return "I/O error: " + path
    }
    return "process failed"
}
