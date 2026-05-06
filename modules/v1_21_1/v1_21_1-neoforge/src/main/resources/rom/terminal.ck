fun glyphPattern(ch: String): String {
    if (ch == "A" || ch == "a") { return "01110100011000111111100011000110001" }
    if (ch == "B" || ch == "b") { return "11110100011000111110100011000111110" }
    if (ch == "C" || ch == "c") { return "01111100001000010000100001000001111" }
    if (ch == "D" || ch == "d") { return "11110100011000110001100011000111110" }
    if (ch == "E" || ch == "e") { return "11111100001000011110100001000011111" }
    if (ch == "F" || ch == "f") { return "11111100001000011110100001000010000" }
    if (ch == "G" || ch == "g") { return "01111100001000010011100011000101111" }
    if (ch == "H" || ch == "h") { return "10001100011000111111100011000110001" }
    if (ch == "I" || ch == "i") { return "11111001000010000100001000010011111" }
    if (ch == "J" || ch == "j") { return "00111000100001000010100101001001100" }
    if (ch == "K" || ch == "k") { return "10001100101010011000101001001010001" }
    if (ch == "L" || ch == "l") { return "10000100001000010000100001000011111" }
    if (ch == "M" || ch == "m") { return "10001110111010110101100011000110001" }
    if (ch == "N" || ch == "n") { return "10001110011010110011100011000110001" }
    if (ch == "O" || ch == "o") { return "01110100011000110001100011000101110" }
    if (ch == "P" || ch == "p") { return "11110100011000111110100001000010000" }
    if (ch == "Q" || ch == "q") { return "01110100011000110001101011001001101" }
    if (ch == "R" || ch == "r") { return "11110100011000111110101001001010001" }
    if (ch == "S" || ch == "s") { return "01111100001000001110000010000111110" }
    if (ch == "T" || ch == "t") { return "11111001000010000100001000010000100" }
    if (ch == "U" || ch == "u") { return "10001100011000110001100011000101110" }
    if (ch == "V" || ch == "v") { return "10001100011000110001100010101000100" }
    if (ch == "W" || ch == "w") { return "10001100011000110101101011010101010" }
    if (ch == "X" || ch == "x") { return "10001010100010000100001000101010001" }
    if (ch == "Y" || ch == "y") { return "10001010100010000100001000010000100" }
    if (ch == "Z" || ch == "z") { return "11111000010001000100010001000011111" }
    if (ch == "0") { return "01110100011001110101110011000101110" }
    if (ch == "1") { return "00100011000010000100001000010001110" }
    if (ch == "2") { return "01110100010000100010001000100011111" }
    if (ch == "3") { return "11110000010000100110000010000111110" }
    if (ch == "4") { return "00010001100101010010111110001000010" }
    if (ch == "5") { return "11111100001111000001000010000111110" }
    if (ch == "6") { return "01111100001000011110100011000101110" }
    if (ch == "7") { return "11111000010001000100010000100001000" }
    if (ch == "8") { return "01110100011000101110100011000101110" }
    if (ch == "9") { return "01110100011000101111000010000111110" }
    if (ch == ".") { return "00000000000000000000000000000000100" }
    if (ch == ":") { return "00000001000010000000001000010000000" }
    if (ch == "/") { return "00001000100001000100010001000000000" }
    if (ch == "-") { return "00000000000000011111000000000000000" }
    if (ch == "_") { return "00000000000000000000000000000011111" }
    if (ch == ">") { return "10000010000010000010001001000000000" }
    if (ch == "`" || ch == "'") { return "00100001000000000000000000000000000" }
    if (ch == "!") { return "00100001000010000100001000000000100" }
    if (ch == "?") { return "01110100010000100010001000000000100" }
    if (ch == "#") { return "01010111110101011111010100000000000" }
    return "11111100011000110001100011000111111"
}

fun drawGlyph(displayId: Int, column: Int, row: Int, ch: String, color: Int) {
    if (ch == " ") {
        return
    }
    val pattern: String = glyphPattern(ch)
    var i: Int = 0
    while i < 35 {
        if (strings::charAt(pattern, i) == "1") {
            val px: Int = i - (i / 5) * 5
            val py: Int = i / 5
            display::fillRect(displayId, column * 6 + px, row * 9 + py, 1, 1, color)
        }
        i = i + 1
    }
}

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
                drawGlyph(displayId, x, y, ch, 2016)
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

fun lineColumn(displayId: Int, screen: String): Int {
    var col: Int = 0
    var i: Int = 0
    val cols: Int = columns(displayId)
    while i < strings::length(screen) {
        val ch: String = strings::charAt(screen, i)
        if (ch == "\n") {
            col = 0
        } else {
            col = col + 1
            if (col >= cols) {
                col = 0
            }
        }
        i = i + 1
    }
    return col
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
    val startColumn: Int = lineColumn(displayId, screen)
    if (row < 0 || row >= rows(displayId)) {
        return
    }
    display::fillRect(displayId, startColumn * 6, row * 9, (cols - startColumn) * 6, 9, 0)
    var x: Int = startColumn
    var i: Int = 0
    while i < strings::length(line) {
        if (x >= cols) {
            display::present(displayId)
            return
        }
        drawGlyph(displayId, x, row, strings::charAt(line, i), 2016)
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
