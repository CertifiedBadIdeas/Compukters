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

fun printHelp() {
    terminal::println("Builtins: help cd pwd reboot shutdown")
    terminal::println("Programs: ls mkdir rmdir nano")
}

fun runExternal(command: String, argument: String) {
    if (process::run(command + ".ck", argument) != 0) {
        terminal::println("Unknown command: " + command)
    }
}

fun handleCd(argument: String) {
    if (strings::isBlank(argument)) {
        terminal::println(displayPath(process::currentDirectory()))
        return
    }
    if (!process::changeDirectory(argument)) {
        terminal::println("Directory not found: " + argument)
    }
}

fun main() {
    terminal::println("Compukter Kraft shell")
    terminal::println("Type `help` for commands.")
    while true {
        val line: String = terminal::readln(displayPath(process::currentDirectory()) + " > ")
        val trimmed: String = strings::trim(line)
        if (strings::isBlank(trimmed)) {
            yield()
        } else {
            val name: String = commandName(trimmed)
            val argument: String = commandArgument(trimmed)
            if (name == "help") {
                printHelp()
            } else if (name == "cd") {
                handleCd(argument)
            } else if (name == "pwd") {
                runExternal("pwd", argument)
            } else if (name == "reboot") {
                system::reboot()
            } else if (name == "shutdown") {
                system::shutdown()
            } else {
                runExternal(name, argument)
            }
        }
    }
}
