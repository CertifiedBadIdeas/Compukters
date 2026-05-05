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
            if (y >= rows) {
                return
            }
            display::fillRect(displayId, x * 6, y * 9, 5, 8, 2016)
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

fun updateInputLine(event: Event, line: String, input: Int): String {
    if (event.name == "char") {
        return line + events::argString(event, 0)
    }
    if (event.name == "paste") {
        return line + events::argString(event, 0)
    }
    if (event.name == "key") {
        if (events::argInt(event, 0) == 257) {
            ipc::write(input, line)
            return ""
        }
    }
    return line
}

pub fun main() {
    val input: Int = ipc::open()
    val output: Int = ipc::open()
    val error: Int = ipc::open()
    process::spawn("shell.ck", input + " " + output + " " + error + " ")

    var displayId: Int = waitDisplay()
    var screen: String = ""
    var line: String = ""

    while true {
        val chunk: String = ipc::tryRead(output) + ipc::tryRead(error)
        if (chunk != "") {
            screen = screen + chunk
            stdout::write(chunk)
            render(displayId, screen)
        } else {
            val event: Event = events::tryPull()
            if (event.name != "") {
                if (event.name == "display_attach" || event.name == "display_resize") {
                    displayId = display::primary()
                    render(displayId, screen)
                } else {
                    val previous: String = line
                    line = updateInputLine(event, line, input)
                    if (previous != "" && line == "") {
                        screen = screen + previous + "\n"
                        stdout::write(previous + "\n")
                        render(displayId, screen)
                    }
                }
            }
            yield()
        }
    }
}
