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
                    render(displayId, screen + line)
                } else if (event.name == "char" || event.name == "paste") {
                    val typed: String = events::argString(event, 0)
                    if (typed != "") {
                        line = line + typed
                        stdout::write(typed)
                        render(displayId, screen + line)
                    }
                } else if (event.name == "key") {
                    val key: Int = events::argInt(event, 0)
                    if (key == 257 || key == 335) {
                        ipc::write(input, line)
                        screen = screen + line + "\n"
                        line = ""
                        stdout::write("\n")
                        render(displayId, screen)
                    }
                }
            }
            yield()
        }
    }
}
