// Toy line-based "nano". Each line typed at the prompt is appended to the
// in-memory buffer; commands starting with ':' control the editor.
//
//   :w   write buffer to the target file
//   :q   quit without saving
//   :wq  write and quit
//   :p   print the current buffer
//   :c   clear the buffer
//
// Anything else is treated as a line of text and appended verbatim.

fun loadInitial(path: String): String {
    if (!filesystem::exists(path)) {
        return ""
    }
    if (filesystem::isDirectory(path)) {
        terminal::println("nano: " + path + " is a directory")
        return ""
    }
    return filesystem::readText(path)
}

fun printBuffer(buffer: String) {
    if (strings::isBlank(buffer)) {
        terminal::println("(empty)")
        return
    }
    terminal::write(buffer)
    if (buffer != "" && buffer != "\n") {
        // Trailing newline so the cursor lands on a fresh line for the prompt.
        terminal::write("\n")
    }
}

fun save(path: String, buffer: String) {
    filesystem::writeText(path, buffer)
    terminal::println("[saved " + path + "]")
}

fun main() {
    val target: String = strings::trim(process::argument())
    if (strings::isBlank(target)) {
        terminal::println("Usage: nano <file>")
        return
    }

    var buffer: String = loadInitial(target)

    terminal::println("nano - editing " + target)
    terminal::println("commands: :w :q :wq :p :c")
    if (buffer != "") {
        terminal::println("---")
        printBuffer(buffer)
        terminal::println("---")
    }

    while true {
        val line: String = terminal::readln("> ")
        if (line == ":q") {
            return
        }
        if (line == ":w") {
            save(target, buffer)
        } else if (line == ":wq") {
            save(target, buffer)
            return
        } else if (line == ":p") {
            printBuffer(buffer)
        } else if (line == ":c") {
            buffer = ""
            terminal::println("[cleared]")
        } else {
            buffer = buffer + line + "\n"
        }
    }
}
