fun waitDisplay(): Int {
    var id: Int = display::primary()
    while id == -1 {
        events::pull("display_attach")
        id = display::primary()
    }
    return id
}

fun render(displayId: Int, text: String) {
    val width: Int = display::width(displayId)
    val height: Int = display::height(displayId)
    if (width < 6 || height < 9) {
        return
    }

    display::clear(displayId, 0)

    val columns: Int = width / 6
    val rows: Int = height / 9
    var x: Int = 0
    var y: Int = 0
    var i: Int = 0
    while i < strings::length(text) {
        val ch: String = strings::charAt(text, i)
        if (ch == "\n") {
            x = 0
            y = y + 1
        } else {
            if (y < rows) {
                display::fillRect(displayId, x * 6, y * 9, 5, 8, 2016)
            }
            x = x + 1
            if (x >= columns) {
                x = 0
                y = y + 1
            }
        }
        i = i + 1
    }

    display::present(displayId)
}

fun columns(displayId: Int): Int {
    return display::width(displayId) / 6
}

fun rows(displayId: Int): Int {
    return display::height(displayId) / 9
}

fun lineRow(displayId: Int, screen: String): Int {
    var row: Int = 0
    var col: Int = 0
    var i: Int = 0
    val cols: Int = columns(displayId)
    while i < strings::length(screen) {
        val ch: String = strings::charAt(screen, i)
        if (ch == "\n") {
            row = row + 1
            col = 0
        } else {
            col = col + 1
            if (col >= cols) {
                col = 0
                row = row + 1
            }
        }
        i = i + 1
    }
    return row
}

fun dropFirstLine(text: String): String {
    var result: String = ""
    var dropped: Bool = false
    var i: Int = 0
    while i < strings::length(text) {
        val ch: String = strings::charAt(text, i)
        if (dropped) {
            result = result + ch
        } else if (ch == "\n") {
            dropped = true
        }
        i = i + 1
    }
    return result
}

fun trimScreen(displayId: Int, screen: String): String {
    var result: String = screen
    val maxRows: Int = rows(displayId) - 1
    while lineRow(displayId, result) > maxRows && result != "" {
        val tailText: String = dropFirstLine(result)
        if (tailText == result) {
            result = ""
        } else {
            result = tailText
        }
    }
    return result
}

fun renderInputLine(displayId: Int, screen: String, line: String) {
    val cols: Int = columns(displayId)
    val row: Int = lineRow(displayId, screen)
    if (row < 0 || row >= rows(displayId)) {
        return
    }
    display::fillRect(displayId, 0, row * 9, cols * 6, 9, 0)
    var x: Int = 0
    var i: Int = 0
    while i < strings::length(line) {
        if (x >= cols) {
            display::present(displayId)
            return
        }
        display::fillRect(displayId, x * 6, row * 9, 5, 8, 2016)
        x = x + 1
        i = i + 1
    }
    display::present(displayId)
}

fun dropLast(text: String): String {
    var result: String = ""
    var i: Int = 0
    while i + 1 < strings::length(text) {
        result = result + strings::charAt(text, i)
        i = i + 1
    }
    return result
}

pub fun main() {
    val input: Int = ipc::open()
    val output: Int = ipc::open()
    val error: Int = ipc::open()
    process::spawn("shell.ck", "stdio-v1 " + input + " " + output + " " + error + " ")

    var displayId: Int = waitDisplay()
    var screen: String = ""
    var line: String = ""

    while true {
        val chunk: String = ipc::tryRead(output) + ipc::tryRead(error)
        if (chunk != "") {
            screen = trimScreen(displayId, screen + chunk)
            render(displayId, screen + line)
        } else {
            val event: Event = events::tryPull()
            if (event.name != "") {
                if (event.name == "display_attach" || event.name == "display_resize") {
                    displayId = display::primary()
                    render(displayId, screen + line)
                } else if (event.name == "char" || event.name == "paste") {
                    val typed: String = events::argString(event, 0)
                    if (typed != "") {
                        line = line + typed
                        renderInputLine(displayId, screen, line)
                    }
                } else if (event.name == "key") {
                    val key: Int = events::argInt(event, 0)
                    if (key == 257 || key == 335) {
                        ipc::write(input, line)
                        screen = trimScreen(displayId, screen + line + "\n")
                        line = ""
                        render(displayId, screen)
                    } else if (key == 259) {
                        if (line != "") {
                            line = dropLast(line)
                            renderInputLine(displayId, screen, line)
                        }
                    }
                }
            }
            yield()
        }
    }
}
