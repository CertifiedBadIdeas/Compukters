import "../stdio.ck" { Stdio, error, fromArgument, println, readLine, write };

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

fun loadInitial(ctx: Stdio, path: String): String {
    if (!filesystem::exists(path)) {
        return ""
    }
    if (filesystem::isDirectory(path)) {
        error(ctx, "nano: " + path + " is a directory")
        return ""
    }
    return filesystem::readText(path)
}

fun printBuffer(ctx: Stdio, buffer: String) {
    if (strings::isBlank(buffer)) {
        println(ctx, "(empty)")
        return
    }
    write(ctx, buffer)
    if (buffer != "" && buffer != "\n") {
        // Trailing newline so the cursor lands on a fresh line for the prompt.
        write(ctx, "\n")
    }
}

fun save(ctx: Stdio, path: String, buffer: String) {
    filesystem::writeText(path, buffer)
    println(ctx, "[saved " + path + "]")
}

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    val target: String = strings::trim(ctx.argument)
    if (strings::isBlank(target)) {
        error(ctx, "Usage: nano <file>")
        return
    }

    var buffer: String = loadInitial(ctx, target)

    println(ctx, "nano - editing " + target)
    println(ctx, "commands: :w :q :wq :p :c")
    if (buffer != "") {
        println(ctx, "---")
        printBuffer(ctx, buffer)
        println(ctx, "---")
    }

    while true {
        write(ctx, "> ")
        val line: String = readLine(ctx)
        if (line == ":q") {
            return
        }
        if (line == ":w") {
            save(ctx, target, buffer)
        } else if (line == ":wq") {
            save(ctx, target, buffer)
            return
        } else if (line == ":p") {
            printBuffer(ctx, buffer)
        } else if (line == ":c") {
            buffer = ""
            println(ctx, "[cleared]")
        } else {
            buffer = buffer + line + "\n"
        }
    }
}
