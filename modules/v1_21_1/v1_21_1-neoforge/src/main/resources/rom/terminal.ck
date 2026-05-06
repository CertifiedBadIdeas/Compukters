pub struct TerminalBuffer { cellsText: String, cursorRow: Int, cursorColumn: Int, displayColumns: Int, displayRows: Int }

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
    if (ch == "/") { return "00001000100001000100010000100010000" }
    if (ch == "-") { return "00000000000000011111000000000000000" }
    if (ch == "_") { return "00000000000000000000000000000011111" }
    if (ch == ">") { return "10000010000010000010001000100010000" }
    if (ch == "<") { return "00001000100010001000001000001000001" }
    if (ch == "`" || ch == "'") { return "00100001000000000000000000000000000" }
    if (ch == "!") { return "00100001000010000100001000000000100" }
    if (ch == "?") { return "01110100010000100010001000000000100" }
    if (ch == "#") { return "01010111110101011111010100000000000" }
    return "11111100011000110001100011000111111"
}

fun drawGlyph(displayId: Int, column: Int, row: Int, ch: String, color: Int) {
    val x: Int = column * 6
    val y: Int = row * 9
    if (ch == " ") {
        display::fillRect(displayId, x, y, 6, 9, 0)
        return
    }
    display::blitMono(displayId, x, y, 5, 7, glyphPattern(ch), color, -1)
}

fun clearCell(displayId: Int, column: Int, row: Int) {
    display::fillRect(displayId, column * 6, row * 9, 6, 9, 0)
}

fun waitDisplay(): Int {
    var id: Int = display::primary()
    while id == -1 {
        events::pull("display_attach")
        id = display::primary()
    }
    return id
}

fun columns(displayId: Int): Int {
    return display::width(displayId) / 6
}

fun rows(displayId: Int): Int {
    return display::height(displayId) / 9
}

fun cellCount(displayId: Int): Int {
    return columns(displayId) * rows(displayId)
}

fun blankCells(count: Int): String {
    var result: String = ""
    var i: Int = 0
    while i < count + 0 {
        result = result + " "
        i = i + 1
    }
    return result
}

fun newTerminalBuffer(displayId: Int): TerminalBuffer {
    return TerminalBuffer(cellsText = blankCells(cellCount(displayId)), cursorRow = 0, cursorColumn = 0, displayColumns = columns(displayId), displayRows = rows(displayId))
}

fun replaceRange(cells: String, start: Int, replacement: String): String {
    var result: String = ""
    var i: Int = 0
    while i < start + 0 {
        result = result + strings::charAt(cells, i)
        i = i + 1
    }
    result = result + replacement
    i = start + strings::length(replacement)
    while i < strings::length(cells) {
        result = result + strings::charAt(cells, i)
        i = i + 1
    }
    return result
}

fun cellAt(cells: String, index: Int): String {
    if (index < 0 || index >= strings::length(cells)) {
        return " "
    }
    return strings::charAt(cells, index)
}

fun clearTextRow(displayId: Int, row: Int) {
    display::fillRect(displayId, 0, row * 9, columns(displayId) * 6, 9, 0)
}

fun renderTextRow(displayId: Int, cells: String, row: Int) {
    clearTextRow(displayId, row)
    var col: Int = 0
    val cols: Int = columns(displayId)
    while col < cols + 0 {
        val ch: String = cellAt(cells, row * cols + col)
        if (ch != " ") {
            display::blitMono(displayId, col * 6, row * 9, 5, 7, glyphPattern(ch), 2016, -1)
        }
        col = col + 1
    }
}

fun renderAllRows(displayId: Int, cells: String) {
    display::clear(displayId, 0)
    var row: Int = 0
    val rs: Int = rows(displayId)
    while row < rs + 0 {
        renderTextRow(displayId, cells, row)
        row = row + 1
    }
    display::present(displayId)
}

fun commitDirtySegment(displayId: Int, cells: String, row: Int, startColumn: Int, text: String): String {
    if (text == "") {
        return cells
    }
    val index: Int = row * columns(displayId) + startColumn
    val updated: String = replaceRange(cells, index, text)
    renderTextRow(displayId, updated, row)
    return updated
}

fun scrollUp(displayId: Int, cells: String): String {
    val cols: Int = columns(displayId)
    val rs: Int = rows(displayId)
    if (rs <= 1) {
        clearTextRow(displayId, 0)
        return blankCells(cellCount(displayId))
    }
    display::copyRect(displayId, 0, 9, cols * 6, (rs - 1) * 9, 0, 0)
    clearTextRow(displayId, rs - 1)
    var result: String = ""
    var i: Int = cols
    while i < strings::length(cells) {
        result = result + strings::charAt(cells, i)
        i = i + 1
    }
    var col: Int = 0
    while col < cols + 0 {
        result = result + " "
        col = col + 1
    }
    return result
}

fun appendText(displayId: Int, buffer: TerminalBuffer, text: String): TerminalBuffer {
    val cols: Int = columns(displayId)
    val rs: Int = rows(displayId)
    if (cols <= 0 || rs <= 0) {
        return buffer
    }

    var cells: String = buffer.cellsText
    var row: Int = buffer.cursorRow
    var col: Int = buffer.cursorColumn
    var dirtyRow: Int = 0 - 1
    var dirtyStartColumn: Int = 0
    var dirtyText: String = ""
    var i: Int = 0
    while i < strings::length(text) {
        val ch: String = strings::charAt(text, i)
        if (ch == "\n") {
            if (dirtyRow >= 0) {
                cells = commitDirtySegment(displayId, cells, dirtyRow, dirtyStartColumn, dirtyText)
                dirtyRow = 0 - 1
                dirtyText = ""
            }
            col = 0
            row = row + 1
            if (row >= rs) {
                cells = scrollUp(displayId, cells)
                row = rs - 1
            }
        } else if (ch == "\r") {
            if (dirtyRow >= 0) {
                cells = commitDirtySegment(displayId, cells, dirtyRow, dirtyStartColumn, dirtyText)
                dirtyRow = 0 - 1
                dirtyText = ""
            }
            col = 0
        } else if (ch == "\b") {
            if (dirtyRow >= 0) {
                cells = commitDirtySegment(displayId, cells, dirtyRow, dirtyStartColumn, dirtyText)
                dirtyRow = 0 - 1
                dirtyText = ""
            }
            if (col > 0) {
                col = col - 1
                cells = replaceRange(cells, row * cols + col, " ")
                clearCell(displayId, col, row)
            }
        } else {
            if (row >= rs) {
                cells = scrollUp(displayId, cells)
                row = rs - 1
            }
            if (dirtyRow < 0) {
                dirtyRow = row
                dirtyStartColumn = col
                dirtyText = ""
            }
            dirtyText = dirtyText + ch
            col = col + 1
            if (col >= cols) {
                if (dirtyRow >= 0) {
                    cells = commitDirtySegment(displayId, cells, dirtyRow, dirtyStartColumn, dirtyText)
                    dirtyRow = 0 - 1
                    dirtyText = ""
                }
                col = 0
                row = row + 1
                if (row >= rs) {
                    cells = scrollUp(displayId, cells)
                    row = rs - 1
                }
            }
        }
        i = i + 1
    }
    if (dirtyRow >= 0) {
        cells = commitDirtySegment(displayId, cells, dirtyRow, dirtyStartColumn, dirtyText)
    }
    display::present(displayId)
    return TerminalBuffer(cellsText = cells, cursorRow = row, cursorColumn = col, displayColumns = cols, displayRows = rs)
}

fun renderInputLine(displayId: Int, buffer: TerminalBuffer, line: String) {
    val cols: Int = columns(displayId)
    val row: Int = buffer.cursorRow
    val startColumn: Int = buffer.cursorColumn
    if (row < 0 || row >= rows(displayId)) {
        return
    }
    if (startColumn < 0 || startColumn >= cols) {
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
    val stream: Int = ipc::open()
    var displayId: Int = waitDisplay()
    display::clear(displayId, 0)
    display::present(displayId)
    var buffer: TerminalBuffer = newTerminalBuffer(displayId)
    var line: String = ""
    process::spawn("shell.ck", "stdio-v1 " + input + " " + stream + " " + stream + " ")

    while true {
        val chunk: String = ipc::tryRead(stream)
        if (chunk != "") {
            buffer = appendText(displayId, buffer, chunk)
            if (line != "") {
                renderInputLine(displayId, buffer, line)
            }
        } else {
            val event: Event = events::tryPull()
            if (event.name != "") {
                if (event.name == "display_attach" || event.name == "display_resize") {
                    displayId = display::primary()
                    if (buffer.displayColumns == columns(displayId) && buffer.displayRows == rows(displayId)) {
                        renderAllRows(displayId, buffer.cellsText)
                        if (line != "") {
                            renderInputLine(displayId, buffer, line)
                        }
                    } else {
                        display::clear(displayId, 0)
                        display::present(displayId)
                        buffer = newTerminalBuffer(displayId)
                        line = ""
                    }
                } else if (event.name == "char" || event.name == "paste") {
                    val typed: String = events::argString(event, 0)
                    if (typed != "") {
                        line = line + typed
                        renderInputLine(displayId, buffer, line)
                    }
                } else if (event.name == "key") {
                    val key: Int = events::argInt(event, 0)
                    if (key == 257 || key == 335) {
                        ipc::write(input, line)
                        line = ""
                    } else if (key == 259) {
                        if (line != "") {
                            line = dropLast(line)
                            renderInputLine(displayId, buffer, line)
                        }
                    }
                }
            }
            yield()
        }
    }
}
