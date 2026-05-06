pub struct TerminalBuffer { cellsText: String, cursorRow: Int, cursorColumn: Int, displayColumns: Int, displayRows: Int }
pub struct Glyph5x7 { row0: Int, row1: Int, row2: Int, row3: Int, row4: Int, row5: Int, row6: Int }

fun glyphRows(ch: String): Glyph5x7 {
    if (ch == "A" || ch == "a") { return Glyph5x7(row0 = 14, row1 = 17, row2 = 17, row3 = 31, row4 = 17, row5 = 17, row6 = 17) }
    if (ch == "B" || ch == "b") { return Glyph5x7(row0 = 30, row1 = 17, row2 = 17, row3 = 30, row4 = 17, row5 = 17, row6 = 30) }
    if (ch == "C" || ch == "c") { return Glyph5x7(row0 = 15, row1 = 16, row2 = 16, row3 = 16, row4 = 16, row5 = 16, row6 = 15) }
    if (ch == "D" || ch == "d") { return Glyph5x7(row0 = 30, row1 = 17, row2 = 17, row3 = 17, row4 = 17, row5 = 17, row6 = 30) }
    if (ch == "E" || ch == "e") { return Glyph5x7(row0 = 31, row1 = 16, row2 = 16, row3 = 30, row4 = 16, row5 = 16, row6 = 31) }
    if (ch == "F" || ch == "f") { return Glyph5x7(row0 = 31, row1 = 16, row2 = 16, row3 = 30, row4 = 16, row5 = 16, row6 = 16) }
    if (ch == "G" || ch == "g") { return Glyph5x7(row0 = 15, row1 = 16, row2 = 16, row3 = 19, row4 = 17, row5 = 17, row6 = 15) }
    if (ch == "H" || ch == "h") { return Glyph5x7(row0 = 17, row1 = 17, row2 = 17, row3 = 31, row4 = 17, row5 = 17, row6 = 17) }
    if (ch == "I" || ch == "i") { return Glyph5x7(row0 = 31, row1 = 4, row2 = 4, row3 = 4, row4 = 4, row5 = 4, row6 = 31) }
    if (ch == "J" || ch == "j") { return Glyph5x7(row0 = 7, row1 = 2, row2 = 2, row3 = 2, row4 = 18, row5 = 18, row6 = 12) }
    if (ch == "K" || ch == "k") { return Glyph5x7(row0 = 17, row1 = 18, row2 = 20, row3 = 24, row4 = 20, row5 = 18, row6 = 17) }
    if (ch == "L" || ch == "l") { return Glyph5x7(row0 = 16, row1 = 16, row2 = 16, row3 = 16, row4 = 16, row5 = 16, row6 = 31) }
    if (ch == "M" || ch == "m") { return Glyph5x7(row0 = 17, row1 = 27, row2 = 21, row3 = 21, row4 = 17, row5 = 17, row6 = 17) }
    if (ch == "N" || ch == "n") { return Glyph5x7(row0 = 17, row1 = 25, row2 = 21, row3 = 19, row4 = 17, row5 = 17, row6 = 17) }
    if (ch == "O" || ch == "o") { return Glyph5x7(row0 = 14, row1 = 17, row2 = 17, row3 = 17, row4 = 17, row5 = 17, row6 = 14) }
    if (ch == "P" || ch == "p") { return Glyph5x7(row0 = 30, row1 = 17, row2 = 17, row3 = 30, row4 = 16, row5 = 16, row6 = 16) }
    if (ch == "Q" || ch == "q") { return Glyph5x7(row0 = 14, row1 = 17, row2 = 17, row3 = 17, row4 = 21, row5 = 18, row6 = 13) }
    if (ch == "R" || ch == "r") { return Glyph5x7(row0 = 30, row1 = 17, row2 = 17, row3 = 30, row4 = 20, row5 = 18, row6 = 17) }
    if (ch == "S" || ch == "s") { return Glyph5x7(row0 = 15, row1 = 16, row2 = 16, row3 = 14, row4 = 1, row5 = 1, row6 = 30) }
    if (ch == "T" || ch == "t") { return Glyph5x7(row0 = 31, row1 = 4, row2 = 4, row3 = 4, row4 = 4, row5 = 4, row6 = 4) }
    if (ch == "U" || ch == "u") { return Glyph5x7(row0 = 17, row1 = 17, row2 = 17, row3 = 17, row4 = 17, row5 = 17, row6 = 14) }
    if (ch == "V" || ch == "v") { return Glyph5x7(row0 = 17, row1 = 17, row2 = 17, row3 = 17, row4 = 17, row5 = 10, row6 = 4) }
    if (ch == "W" || ch == "w") { return Glyph5x7(row0 = 17, row1 = 17, row2 = 17, row3 = 21, row4 = 21, row5 = 21, row6 = 10) }
    if (ch == "X" || ch == "x") { return Glyph5x7(row0 = 17, row1 = 10, row2 = 4, row3 = 4, row4 = 4, row5 = 10, row6 = 17) }
    if (ch == "Y" || ch == "y") { return Glyph5x7(row0 = 17, row1 = 10, row2 = 4, row3 = 4, row4 = 4, row5 = 4, row6 = 4) }
    if (ch == "Z" || ch == "z") { return Glyph5x7(row0 = 31, row1 = 1, row2 = 2, row3 = 4, row4 = 8, row5 = 16, row6 = 31) }
    if (ch == "0") { return Glyph5x7(row0 = 14, row1 = 17, row2 = 19, row3 = 21, row4 = 25, row5 = 17, row6 = 14) }
    if (ch == "1") { return Glyph5x7(row0 = 4, row1 = 12, row2 = 4, row3 = 4, row4 = 4, row5 = 4, row6 = 14) }
    if (ch == "2") { return Glyph5x7(row0 = 14, row1 = 17, row2 = 1, row3 = 2, row4 = 4, row5 = 8, row6 = 31) }
    if (ch == "3") { return Glyph5x7(row0 = 30, row1 = 1, row2 = 1, row3 = 6, row4 = 1, row5 = 1, row6 = 30) }
    if (ch == "4") { return Glyph5x7(row0 = 2, row1 = 6, row2 = 10, row3 = 18, row4 = 31, row5 = 2, row6 = 2) }
    if (ch == "5") { return Glyph5x7(row0 = 31, row1 = 16, row2 = 30, row3 = 1, row4 = 1, row5 = 1, row6 = 30) }
    if (ch == "6") { return Glyph5x7(row0 = 15, row1 = 16, row2 = 16, row3 = 30, row4 = 17, row5 = 17, row6 = 14) }
    if (ch == "7") { return Glyph5x7(row0 = 31, row1 = 1, row2 = 2, row3 = 4, row4 = 8, row5 = 8, row6 = 8) }
    if (ch == "8") { return Glyph5x7(row0 = 14, row1 = 17, row2 = 17, row3 = 14, row4 = 17, row5 = 17, row6 = 14) }
    if (ch == "9") { return Glyph5x7(row0 = 14, row1 = 17, row2 = 17, row3 = 15, row4 = 1, row5 = 1, row6 = 30) }
    if (ch == ".") { return Glyph5x7(row0 = 0, row1 = 0, row2 = 0, row3 = 0, row4 = 0, row5 = 0, row6 = 4) }
    if (ch == ":") { return Glyph5x7(row0 = 0, row1 = 4, row2 = 4, row3 = 0, row4 = 4, row5 = 4, row6 = 0) }
    if (ch == "/") { return Glyph5x7(row0 = 1, row1 = 2, row2 = 2, row3 = 4, row4 = 8, row5 = 8, row6 = 16) }
    if (ch == "-") { return Glyph5x7(row0 = 0, row1 = 0, row2 = 0, row3 = 31, row4 = 0, row5 = 0, row6 = 0) }
    if (ch == "_") { return Glyph5x7(row0 = 0, row1 = 0, row2 = 0, row3 = 0, row4 = 0, row5 = 0, row6 = 31) }
    if (ch == ">") { return Glyph5x7(row0 = 16, row1 = 8, row2 = 4, row3 = 2, row4 = 4, row5 = 8, row6 = 16) }
    if (ch == "<") { return Glyph5x7(row0 = 1, row1 = 2, row2 = 4, row3 = 8, row4 = 4, row5 = 2, row6 = 1) }
    if (ch == "`" || ch == "'") { return Glyph5x7(row0 = 4, row1 = 4, row2 = 0, row3 = 0, row4 = 0, row5 = 0, row6 = 0) }
    if (ch == "!") { return Glyph5x7(row0 = 4, row1 = 4, row2 = 4, row3 = 4, row4 = 4, row5 = 0, row6 = 4) }
    if (ch == "?") { return Glyph5x7(row0 = 14, row1 = 17, row2 = 1, row3 = 2, row4 = 4, row5 = 0, row6 = 4) }
    if (ch == "#") { return Glyph5x7(row0 = 10, row1 = 31, row2 = 10, row3 = 31, row4 = 10, row5 = 0, row6 = 0) }
    return Glyph5x7(row0 = 31, row1 = 17, row2 = 17, row3 = 17, row4 = 17, row5 = 17, row6 = 31)
}

fun drawGlyph(displayId: Int, column: Int, row: Int, ch: String, color: Int) {
    val x: Int = column * 6
    val y: Int = row * 9
    if (ch == " ") {
        display::fillRect(displayId, x, y, 6, 9, 0)
        return
    }
    val glyph: Glyph5x7 = glyphRows(ch)
    display::blitMono5x7(displayId, x, y, glyph.row0, glyph.row1, glyph.row2, glyph.row3, glyph.row4, glyph.row5, glyph.row6, color, -1)
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
                val glyph: Glyph5x7 = glyphRows(ch)
                display::blitMono5x7(displayId, col * 6, row * 9, glyph.row0, glyph.row1, glyph.row2, glyph.row3, glyph.row4, glyph.row5, glyph.row6, 2016, -1)
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
                        ipc::write(input, line + "\n")
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
