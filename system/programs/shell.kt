/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import compukter.process.Process
import compukter.terminal.Terminal

suspend fun main() {
    var line = ""
    Terminal.write("> ")
    while (true) {
        val event = Terminal.awaitEvent()
        if (event == 1) {
            line = appendText(line, Terminal.eventText())
        } else if (event == 2) {
            val key = Terminal.eventKey()
            val action = Terminal.eventAction()
            if (key == 13 && action == 1) {
                Terminal.write("\n")
                if (line != "") {
                    if (line == "help") {
                        Terminal.write("help echo clear\n")
                    } else if (line == "echo") {
                        Terminal.write("\n")
                    } else if (line.length >= 5 && line.substring(0, 5) == "echo ") {
                        Terminal.write(line.substring(5, line.length) + "\n")
                    } else if (line == "clear") {
                        Terminal.clear()
                    } else {
                        var commandEnd = 0
                        while (commandEnd < line.length && line[commandEnd] != ' ') commandEnd = commandEnd + 1
                        if (commandEnd != line.length) {
                            Terminal.write("unknown command: " + line.substring(0, commandEnd) + "\n")
                        } else {
                            val path = if (line[0] == '/') line else "/home/" + line
                            val result = Process.run(path, 7)
                            if (result != 0) writeProcessFailure(result)
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
        Terminal.finishEvent()
    }
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

private fun writeProcessFailure(result: Int) {
    Terminal.write("process failed: ")
    if (result == 1) Terminal.write("1")
    else if (result == 2) Terminal.write("2")
    else if (result == 3) Terminal.write("3")
    else if (result == 4) Terminal.write("4")
    else if (result == 5) Terminal.write("5")
    else if (result == 6) Terminal.write("6")
    else if (result == 7) Terminal.write("7")
    else if (result == 8) Terminal.write("8")
    else if (result == 9) Terminal.write("9")
    else if (result == 10) Terminal.write("10")
    else if (result == 11) Terminal.write("11")
    else if (result == 12) Terminal.write("12")
    else if (result == 13) Terminal.write("13")
    else if (result == 14) Terminal.write("14")
    else if (result == 15) Terminal.write("15")
    else if (result == 16) Terminal.write("16")
    else Terminal.write("unknown")
    Terminal.write("\n")
}
