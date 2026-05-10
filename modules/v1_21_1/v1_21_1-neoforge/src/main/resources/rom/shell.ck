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

fun stripCkSuffix(name: String): String {
    val length: Int = strings::length(name)
    if (length <= 3) {
        return name
    }
    if (strings::slice(name, length - 3, length) != ".ck") {
        return name
    }
    return strings::slice(name, 0, length - 3)
}

fun commandListFromBin(listing: String): String {
    var rest: String = strings::trim(listing)
    var result: String = ""
    while !strings::isBlank(rest) {
        val entry: String = strings::beforeSpace(rest)
        rest = strings::afterSpace(rest)
        if (strings::slice(entry, strings::length(entry) - 1, strings::length(entry)) != "/") {
            if (result != "") {
                result = result + " "
            }
            result = result + stripCkSuffix(entry)
        }
    }
    return result
}

fun printHelp(ctx: Stdio) {
    println(ctx, "Builtins: help cd reboot shutdown")
    println(ctx, "Programs: " + commandListFromBin(filesystem::list("bin")))
}

fun runExternal(ctx: Stdio, command: String, argument: String) {
    val path: String = "bin/" + command + ".ck"
    if (!exists(path)) {
        error(ctx, "Unknown command: " + command)
        return
    }
    val code: Int = process::run(path, encode(ctx, argument))
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
        } else {
            val name: String = commandName(trimmed)
            val argument: String = commandArgument(trimmed)
            if (name == "help") {
                printHelp(ctx)
            } else if (name == "cd") {
                handleCd(ctx, argument)
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
