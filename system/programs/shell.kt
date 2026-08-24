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

import process.run

suspend fun main() {
    var line = ""
    terminalWrite("> ")
    while (true) {
        val event = terminalAwaitEvent()
        if (event == 1) {
            line = appendText(line, terminalEventText())
        } else if (event == 2) {
            val key = terminalEventKey()
            val action = terminalEventAction()
            if (key == 13 && action == 1) {
                terminalWrite("\n")
                if (line != "") {
                    if (line == "help") {
                        terminalWrite("help echo clear\n")
                    } else if (line == "echo") {
                        terminalWrite("\n")
                    } else if (line.length >= 5 && line.substring(0, 5) == "echo ") {
                        terminalWrite(line.substring(5, line.length) + "\n")
                    } else if (line == "clear") {
                        terminalClear()
                    } else {
                        var commandEnd = 0
                        while (commandEnd < line.length && line[commandEnd] != ' ') commandEnd = commandEnd + 1
                        if (commandEnd != line.length) {
                            terminalWrite("unknown command: " + line.substring(0, commandEnd) + "\n")
                        } else {
                            val path = if (line[0] == '/') line else "/home/" + line
                            val result = run(path, 7)
                            if (result != 0) writeProcessFailure(result)
                        }
                    }
                }
                line = ""
                terminalWrite("> ")
            } else if (
                key == 8 &&
                (action == 1 || action == 2) &&
                line.length > 0
            ) {
                line = eraseLastScalar(line)
                terminalErasePrevious()
            }
        }
        terminalFinishEvent()
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
            terminalWrite(accepted)
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
    terminalWrite("process failed: ")
    if (result == 1) terminalWrite("1")
    else if (result == 2) terminalWrite("2")
    else if (result == 3) terminalWrite("3")
    else if (result == 4) terminalWrite("4")
    else if (result == 5) terminalWrite("5")
    else if (result == 6) terminalWrite("6")
    else if (result == 7) terminalWrite("7")
    else if (result == 8) terminalWrite("8")
    else if (result == 9) terminalWrite("9")
    else if (result == 10) terminalWrite("10")
    else if (result == 11) terminalWrite("11")
    else if (result == 12) terminalWrite("12")
    else if (result == 13) terminalWrite("13")
    else if (result == 14) terminalWrite("14")
    else if (result == 15) terminalWrite("15")
    else if (result == 16) terminalWrite("16")
    else terminalWrite("unknown")
    terminalWrite("\n")
}
