import filesystem { exists };
import "stdio.ck" { Stdio, encode, error, fromArgument, println, readLine, write };

fun displayPath(path: String): String {
    if (path == "") {
        return "/"
    }
    return "/" + path
}

fun commandName(line: String): String {
    return strings::beforeSpace(strings::trim(line))
}

fun commandArgument(line: String): String {
    return strings::afterSpace(strings::trim(line))
}

fun printHelp(ctx: Stdio) {
    println(ctx, "Builtins: help cd pwd reboot shutdown")
    println(ctx, "Programs: ls mkdir rmdir nano yes")
}

fun runExternal(ctx: Stdio, command: String, argument: String) {
    if (!exists(command + ".ck")) {
        error(ctx, "Unknown command: " + command)
        return
    }
    val code: Int = process::run(command + ".ck", encode(ctx, argument))
    if (code != 0) {
        error(ctx, "Command failed: " + command)
    }
}

fun handleCd(ctx: Stdio, argument: String) {
    if (strings::isBlank(argument)) {
        println(ctx, displayPath(process::currentDirectory()))
        return
    }
    if (!process::changeDirectory(argument)) {
        error(ctx, "Directory not found: " + argument)
    }
}

pub fun main() {
    val ctx: Stdio = fromArgument(process::argument())
    println(ctx, "Compukter Kraft shell")
    println(ctx, "Type `help` for commands.")
    while true {
        write(ctx, displayPath(process::currentDirectory()) + " > ")
        val line: String = readLine(ctx)
        val trimmed: String = strings::trim(line)
        if (strings::isBlank(trimmed)) {
            yield()
        } else {
            val name: String = commandName(trimmed)
            val argument: String = commandArgument(trimmed)
            if (name == "help") {
                printHelp(ctx)
            } else if (name == "cd") {
                handleCd(ctx, argument)
            } else if (name == "pwd") {
                runExternal(ctx, "pwd", argument)
            } else if (name == "reboot") {
                system::reboot()
            } else if (name == "shutdown") {
                system::shutdown()
            } else {
                runExternal(ctx, name, argument)
            }
        }
    }
}
