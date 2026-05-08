pub struct TerminalBuffer {
    cellsText: String,
    historyCells: String,
    cursorRow: Int,
    cursorColumn: Int,
    displayColumns: Int,
    displayRows: Int,
    historyRows: Int,
    viewportOffset: Int
}

fun glyphBits(ch: String): Long {
    if (ch == "A" || ch == "a") { return 0b01110100011000111111100011000110001L }
    if (ch == "B" || ch == "b") { return 0b11110100011000111110100011000111110L }
    if (ch == "C" || ch == "c") { return 0b01111100001000010000100001000001111L }
    if (ch == "D" || ch == "d") { return 0b11110100011000110001100011000111110L }
    if (ch == "E" || ch == "e") { return 0b11111100001000011110100001000011111L }
    if (ch == "F" || ch == "f") { return 0b11111100001000011110100001000010000L }
    if (ch == "G" || ch == "g") { return 0b01111100001000010011100011000101111L }
    if (ch == "H" || ch == "h") { return 0b10001100011000111111100011000110001L }
    if (ch == "I" || ch == "i") { return 0b11111001000010000100001000010011111L }
    if (ch == "J" || ch == "j") { return 0b00111000100001000010100101001001100L }
    if (ch == "K" || ch == "k") { return 0b10001100101010011000101001001010001L }
    if (ch == "L" || ch == "l") { return 0b10000100001000010000100001000011111L }
    if (ch == "M" || ch == "m") { return 0b10001110111010110101100011000110001L }
    if (ch == "N" || ch == "n") { return 0b10001110011010110011100011000110001L }
    if (ch == "O" || ch == "o") { return 0b01110100011000110001100011000101110L }
    if (ch == "P" || ch == "p") { return 0b11110100011000111110100001000010000L }
    if (ch == "Q" || ch == "q") { return 0b01110100011000110001101011001001101L }
    if (ch == "R" || ch == "r") { return 0b11110100011000111110101001001010001L }
    if (ch == "S" || ch == "s") { return 0b01111100001000001110000010000111110L }
    if (ch == "T" || ch == "t") { return 0b11111001000010000100001000010000100L }
    if (ch == "U" || ch == "u") { return 0b10001100011000110001100011000101110L }
    if (ch == "V" || ch == "v") { return 0b10001100011000110001100010101000100L }
    if (ch == "W" || ch == "w") { return 0b10001100011000110101101011010101010L }
    if (ch == "X" || ch == "x") { return 0b10001010100010000100001000101010001L }
    if (ch == "Y" || ch == "y") { return 0b10001010100010000100001000010000100L }
    if (ch == "Z" || ch == "z") { return 0b11111000010001000100010001000011111L }
    if (ch == "0") { return 0b01110100011001110101110011000101110L }
    if (ch == "1") { return 0b00100011000010000100001000010001110L }
    if (ch == "2") { return 0b01110100010000100010001000100011111L }
    if (ch == "3") { return 0b11110000010000100110000010000111110L }
    if (ch == "4") { return 0b00010001100101010010111110001000010L }
    if (ch == "5") { return 0b11111100001111000001000010000111110L }
    if (ch == "6") { return 0b01111100001000011110100011000101110L }
    if (ch == "7") { return 0b11111000010001000100010000100001000L }
    if (ch == "8") { return 0b01110100011000101110100011000101110L }
    if (ch == "9") { return 0b01110100011000101111000010000111110L }
    if (ch == ".") { return 0b00000000000000000000000000000000100L }
    if (ch == ":") { return 0b00000001000010000000001000010000000L }
    if (ch == "/") { return 0b00001000100001000100010000100010000L }
    if (ch == "-") { return 0b00000000000000011111000000000000000L }
    if (ch == "_") { return 0b00000000000000000000000000000011111L }
    if (ch == ">") { return 0b10000010000010000010001000100010000L }
    if (ch == "<") { return 0b00001000100010001000001000001000001L }
    if (ch == "`" || ch == "'") { return 0b00100001000000000000000000000000000L }
    if (ch == "!") { return 0b00100001000010000100001000000000100L }
    if (ch == "?") { return 0b01110100010000100010001000000000100L }
    if (ch == "#") { return 0b01010111110101011111010100000000000L }
    return 0b11111100011000110001100011000111111L
}

fun drawGlyph(displayId: Int, column: Int, row: Int, ch: String, color: Int) {
    val x: Int = column * 6
    val y: Int = row * 9
    if (ch == " ") {
        display::fillRect(displayId, x, y, 6, 9, 0)
        return
    }
    val glyph: Long = glyphBits(ch)
    display::blitMono5x7Packed(displayId, x, y, glyph, color, -1)
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
    val cells: String = blankCells(cellCount(displayId))
    return TerminalBuffer(
        cellsText = cells,
        historyCells = cells,
        cursorRow = 0,
        cursorColumn = 0,
        displayColumns = columns(displayId),
        displayRows = rows(displayId),
        historyRows = rows(displayId),
        viewportOffset = 0
    )
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
            val glyph: Long = glyphBits(ch)
            display::blitMono5x7Packed(displayId, col * 6, row * 9, glyph, 2016, -1)
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
    return replaceRange(cells, index, text)
}

fun appendBlankHistoryRows(cells: String, cols: Int, count: Int): String {
    var result: String = cells
    var row: Int = 0
    while row < count + 0 {
        var col: Int = 0
        while col < cols + 0 {
            result = result + " "
            col = col + 1
        }
        row = row + 1
    }
    return result
}

fun historyRowStart(historyRows: Int, displayRows: Int, viewportOffset: Int): Int {
    var start: Int = historyRows - displayRows - viewportOffset
    if (start < 0) {
        start = 0
    }
    return start
}

fun viewportCells(historyCells: String, historyRows: Int, cols: Int, rs: Int, viewportOffset: Int): String {
    var result: String = ""
    val startRow: Int = historyRowStart(historyRows, rs, viewportOffset)
    var visibleRow: Int = 0
    while visibleRow < rs + 0 {
        val sourceRow: Int = startRow + visibleRow
        var col: Int = 0
        while col < cols + 0 {
            if (sourceRow >= 0 && sourceRow < historyRows) {
                result = result + cellAt(historyCells, sourceRow * cols + col)
            } else {
                result = result + " "
            }
            col = col + 1
        }
        visibleRow = visibleRow + 1
    }
    return result
}

fun renderViewport(displayId: Int, buffer: TerminalBuffer) {
    if (buffer.viewportOffset == 0) {
        renderAllRows(displayId, buffer.cellsText)
        return
    }
    renderAllRows(
        displayId,
        viewportCells(
            buffer.historyCells,
            buffer.historyRows,
            buffer.displayColumns,
            buffer.displayRows,
            buffer.viewportOffset
        )
    )
}

fun scrollViewportBy(displayId: Int, buffer: TerminalBuffer, deltaRows: Int): TerminalBuffer {
    var maxOffset: Int = buffer.historyRows - buffer.displayRows
    if (maxOffset < 0) {
        maxOffset = 0
    }
    var nextOffset: Int = buffer.viewportOffset + deltaRows
    if (nextOffset < 0) {
        nextOffset = 0
    }
    if (nextOffset > maxOffset) {
        nextOffset = maxOffset
    }
    val updated: TerminalBuffer = TerminalBuffer(
        cellsText = buffer.cellsText,
        historyCells = buffer.historyCells,
        cursorRow = buffer.cursorRow,
        cursorColumn = buffer.cursorColumn,
        displayColumns = buffer.displayColumns,
        displayRows = buffer.displayRows,
        historyRows = buffer.historyRows,
        viewportOffset = nextOffset
    )
    renderViewport(displayId, updated)
    return updated
}

fun followBottom(displayId: Int, buffer: TerminalBuffer): TerminalBuffer {
    if (buffer.viewportOffset == 0) {
        return buffer
    }
    val updated: TerminalBuffer = TerminalBuffer(
        cellsText = buffer.cellsText,
        historyCells = buffer.historyCells,
        cursorRow = buffer.cursorRow,
        cursorColumn = buffer.cursorColumn,
        displayColumns = buffer.displayColumns,
        displayRows = buffer.displayRows,
        historyRows = buffer.historyRows,
        viewportOffset = 0
    )
    renderViewport(displayId, updated)
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
    var history: String = buffer.historyCells
    var row: Int = buffer.cursorRow
    var col: Int = buffer.cursorColumn
    var historyRows: Int = buffer.historyRows
    var i: Int = 0
    while i < strings::length(text) {
        val ch: String = strings::charAt(text, i)
        if (ch == "\n") {
            col = 0
            row = row + 1
            if (row >= historyRows) {
                history = appendBlankHistoryRows(history, cols, 1)
                historyRows = historyRows + 1
            }
            if (row >= rs) {
                cells = scrollUp(displayId, cells)
            }
        } else if (ch == "\r") {
            col = 0
        } else if (ch == "\b") {
            if (col > 0) {
                col = col - 1
                history = replaceRange(history, row * cols + col, " ")
            }
        } else {
            if (row >= historyRows) {
                history = appendBlankHistoryRows(history, cols, 1)
                historyRows = historyRows + 1
            }
            history = commitDirtySegment(displayId, history, row, col, ch)
            col = col + 1
            if (col >= cols) {
                col = 0
                row = row + 1
                if (row >= historyRows) {
                    history = appendBlankHistoryRows(history, cols, 1)
                    historyRows = historyRows + 1
                }
            }
        }
        i = i + 1
    }
    cells = viewportCells(history, historyRows, cols, rs, 0)
    val updated: TerminalBuffer = TerminalBuffer(
        cellsText = cells,
        historyCells = history,
        cursorRow = row,
        cursorColumn = col,
        displayColumns = cols,
        displayRows = rs,
        historyRows = historyRows,
        viewportOffset = buffer.viewportOffset
    )
    if (buffer.viewportOffset == 0) {
        renderViewport(displayId, updated)
    }
    return updated
}

fun inputOverlayRows(displayId: Int, buffer: TerminalBuffer, line: String): Int {
    val cols: Int = columns(displayId)
    if (cols <= 0) {
        return 0
    }
    if (strings::length(line) <= 0) {
        return 1
    }
    var rowsUsed: Int = 1
    var x: Int = buffer.cursorColumn
    var i: Int = 0
    while i < strings::length(line) {
        x = x + 1
        if (x >= cols) {
            x = 0
            if (i + 1 < strings::length(line)) {
                rowsUsed = rowsUsed + 1
            }
        }
        i = i + 1
    }
    return rowsUsed
}

fun clearRenderedInputLine(displayId: Int, buffer: TerminalBuffer, previousLine: String) {
    val rowsUsed: Int = inputOverlayRows(displayId, buffer, previousLine)
    var visibleCells: String = buffer.cellsText
    if (buffer.viewportOffset != 0) {
        visibleCells =
            viewportCells(
                buffer.historyCells,
                buffer.historyRows,
                buffer.displayColumns,
                buffer.displayRows,
                buffer.viewportOffset
            )
    }
    var rowOffset: Int = 0
    while rowOffset < rowsUsed + 0 {
        val row: Int = (buffer.cursorRow - historyRowStart(buffer.historyRows, buffer.displayRows, buffer.viewportOffset)) + rowOffset
        if (row >= 0 && row < rows(displayId)) {
            clearTextRow(displayId, row)
            renderTextRow(displayId, visibleCells, row)
        }
        rowOffset = rowOffset + 1
    }
}

fun renderInputLine(displayId: Int, buffer: TerminalBuffer, previousLine: String, line: String) {
    val cols: Int = columns(displayId)
    val rs: Int = rows(displayId)
    var x: Int = buffer.cursorColumn
    var y: Int = buffer.cursorRow - historyRowStart(buffer.historyRows, buffer.displayRows, buffer.viewportOffset)
    if (rs <= 0 || cols <= 0) {
        return
    }
    if (y < 0 || y >= rs) {
        return
    }
    if (x < 0 || x >= cols) {
        return
    }
    clearRenderedInputLine(displayId, buffer, previousLine)
    var i: Int = 0
    while i < strings::length(line) {
        if (y >= rs) {
            display::present(displayId)
            return
        }
        if (x >= cols) {
            x = 0
            y = y + 1
            if (y >= rs) {
                display::present(displayId)
                return
            }
        }
        drawGlyph(displayId, x, y, strings::charAt(line, i), 2016)
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
    var renderedLine: String = ""
    process::spawn("shell.ck", "stdio-v1 " + input + " " + stream + " " + stream + " ")

    while true {
        val signal: Poll = runtime::poll(stream)
        if (signal.kind == "ipc") {
            val chunk: String = signal.text
            buffer = appendText(displayId, buffer, chunk)
            if (buffer.viewportOffset == 0 && line != "") {
                renderInputLine(displayId, buffer, renderedLine, line)
                renderedLine = line
            }
        } else if (signal.kind == "event") {
            val event: Event = signal.event
            if (event.name == "display_attach" || event.name == "display_resize") {
                displayId = display::primary()
                if (buffer.displayColumns == columns(displayId) && buffer.displayRows == rows(displayId)) {
                    renderViewport(displayId, buffer)
                    if (buffer.viewportOffset == 0 && line != "") {
                        renderInputLine(displayId, buffer, renderedLine, line)
                        renderedLine = line
                    }
                } else {
                    display::clear(displayId, 0)
                    display::present(displayId)
                    buffer = newTerminalBuffer(displayId)
                    line = ""
                    renderedLine = ""
                }
            } else if (event.name == "char" || event.name == "paste") {
                val typed: String = events::argString(event, 0)
                if (typed != "") {
                    buffer = followBottom(displayId, buffer)
                    line = line + typed
                    renderInputLine(displayId, buffer, renderedLine, line)
                    renderedLine = line
                }
            } else if (event.name == "key") {
                val key: Int = events::argInt(event, 0)
                if (key == 257 || key == 335) {
                    buffer = followBottom(displayId, buffer)
                    ipc::write(input, line + "\n")
                    line = ""
                    renderedLine = ""
                } else if (key == 259) {
                    if (line != "") {
                        buffer = followBottom(displayId, buffer)
                        line = dropLast(line)
                        renderInputLine(displayId, buffer, renderedLine, line)
                        renderedLine = line
                    }
                } else if (key == 266) {
                    var pageRows: Int = rows(displayId) - 1
                    if (pageRows <= 0) {
                        pageRows = 1
                    }
                    buffer = scrollViewportBy(displayId, buffer, pageRows)
                } else if (key == 267) {
                    var pageRows: Int = rows(displayId) - 1
                    if (pageRows <= 0) {
                        pageRows = 1
                    }
                    buffer = scrollViewportBy(displayId, buffer, 0 - pageRows)
                    if (buffer.viewportOffset == 0 && line != "") {
                        renderInputLine(displayId, buffer, renderedLine, line)
                        renderedLine = line
                    }
                }
            }
        }
    }
}
