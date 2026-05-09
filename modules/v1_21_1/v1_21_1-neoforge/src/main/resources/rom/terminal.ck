pub struct TerminalBuffer {
    cellsText: String,
    historyCells: String,
    glyphs: Array<Long>,
    cursorRow: Int,
    cursorColumn: Int,
    displayColumns: Int,
    displayRows: Int,
    historyRows: Int,
    viewportOffset: Int
}

pub struct TerminalEventResult {
    displayId: Int,
    buffer: TerminalBuffer,
    line: String,
    renderedLine: String,
    renderInput: Bool
}

fun inputBatchLimit(): Int {
    return 64
}

fun unknownGlyphBits(): Long {
    return 0b11111100011000110001100011000111111L
}

fun asciiGlyphs(): Array<Long> {
    val glyphs: Array<Long> = Array<Long>(size = 128, default = unknownGlyphBits())
    glyphs[33] = 0b00100001000010000100001000000000100L
    glyphs[35] = 0b01010111110101011111010100000000000L
    glyphs[39] = 0b00100001000000000000000000000000000L
    glyphs[45] = 0b00000000000000011111000000000000000L
    glyphs[46] = 0b00000000000000000000000000000000100L
    glyphs[47] = 0b00001000100001000100010000100010000L
    glyphs[48] = 0b01110100011001110101110011000101110L
    glyphs[49] = 0b00100011000010000100001000010001110L
    glyphs[50] = 0b01110100010000100010001000100011111L
    glyphs[51] = 0b11110000010000100110000010000111110L
    glyphs[52] = 0b00010001100101010010111110001000010L
    glyphs[53] = 0b11111100001111000001000010000111110L
    glyphs[54] = 0b01111100001000011110100011000101110L
    glyphs[55] = 0b11111000010001000100010000100001000L
    glyphs[56] = 0b01110100011000101110100011000101110L
    glyphs[57] = 0b01110100011000101111000010000111110L
    glyphs[58] = 0b00000001000010000000001000010000000L
    glyphs[60] = 0b00001000100010001000001000001000001L
    glyphs[62] = 0b10000010000010000010001000100010000L
    glyphs[63] = 0b01110100010000100010001000000000100L
    glyphs[65] = 0b01110100011000111111100011000110001L
    glyphs[66] = 0b11110100011000111110100011000111110L
    glyphs[67] = 0b01111100001000010000100001000001111L
    glyphs[68] = 0b11110100011000110001100011000111110L
    glyphs[69] = 0b11111100001000011110100001000011111L
    glyphs[70] = 0b11111100001000011110100001000010000L
    glyphs[71] = 0b01111100001000010011100011000101111L
    glyphs[72] = 0b10001100011000111111100011000110001L
    glyphs[73] = 0b11111001000010000100001000010011111L
    glyphs[74] = 0b00111000100001000010100101001001100L
    glyphs[75] = 0b10001100101010011000101001001010001L
    glyphs[76] = 0b10000100001000010000100001000011111L
    glyphs[77] = 0b10001110111010110101100011000110001L
    glyphs[78] = 0b10001110011010110011100011000110001L
    glyphs[79] = 0b01110100011000110001100011000101110L
    glyphs[80] = 0b11110100011000111110100001000010000L
    glyphs[81] = 0b01110100011000110001101011001001101L
    glyphs[82] = 0b11110100011000111110101001001010001L
    glyphs[83] = 0b01111100001000001110000010000111110L
    glyphs[84] = 0b11111001000010000100001000010000100L
    glyphs[85] = 0b10001100011000110001100011000101110L
    glyphs[86] = 0b10001100011000110001100010101000100L
    glyphs[87] = 0b10001100011000110101101011010101010L
    glyphs[88] = 0b10001010100010000100001000101010001L
    glyphs[89] = 0b10001010100010000100001000010000100L
    glyphs[90] = 0b11111000010001000100010001000011111L
    glyphs[95] = 0b00000000000000000000000000000011111L
    glyphs[96] = 0b00100001000000000000000000000000000L
    var code: Int = 65
    while code < 91 {
        glyphs[code + 32] = glyphs[code]
        code = code + 1
    }
    return glyphs
}

fun glyphBits(glyphs: Array<Long>, ch: String): Long {
    val code: Int = strings::charCodeAt(ch, 0)
    if (code >= 0 && code < 128) {
        return glyphs[code]
    }
    return unknownGlyphBits()
}

fun drawGlyph(displayId: Int, glyphs: Array<Long>, column: Int, row: Int, ch: String, color: Int) {
    val x: Int = column * 6
    val y: Int = row * 9
    if (ch == " ") {
        display::fillRect(displayId, x, y, 6, 9, 0)
        return
    }
    val glyph: Long = glyphBits(glyphs, ch)
    display::blitMono5x7Packed(displayId, x, y, glyph, color, -1)
}

fun drawGlyphRun(displayId: Int, column: Int, row: Int, text: String, color: Int) {
    if (text == "") {
        return
    }
    display::blitMono5x7Text(displayId, column * 6, row * 9, text, color, -1)
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
    return strings::repeat(" ", count)
}

fun newTerminalBuffer(displayId: Int): TerminalBuffer {
    val cells: String = blankCells(cellCount(displayId))
    return TerminalBuffer(
        cellsText = cells,
        historyCells = cells,
        glyphs = asciiGlyphs(),
        cursorRow = 0,
        cursorColumn = 0,
        displayColumns = columns(displayId),
        displayRows = rows(displayId),
        historyRows = rows(displayId),
        viewportOffset = 0
    )
}

fun replaceRange(cells: String, start: Int, replacement: String): String {
    return strings::replaceRange(cells, start, replacement)
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

fun renderRowCells(displayId: Int, glyphs: Array<Long>, row: Int, rowCells: String) {
    clearTextRow(displayId, row)
    var col: Int = 0
    val cols: Int = columns(displayId)
    var run: String = ""
    var runColumn: Int = 0
    while col < cols + 0 {
        val ch: String = cellAt(rowCells, col)
        if (ch != " ") {
            if (run == "") {
                runColumn = col
            }
            run = run + ch
        } else {
            drawGlyphRun(displayId, runColumn, row, run, 2016)
            run = ""
        }
        col = col + 1
    }
    drawGlyphRun(displayId, runColumn, row, run, 2016)
}

fun renderTextRow(displayId: Int, glyphs: Array<Long>, cells: String, row: Int) {
    val cols: Int = columns(displayId)
    renderRowCells(displayId, glyphs, row, strings::slice(cells, row * cols, (row + 1) * cols))
}

fun renderAllRows(displayId: Int, glyphs: Array<Long>, cells: String) {
    display::clear(displayId, 0)
    var row: Int = 0
    val rs: Int = rows(displayId)
    while row < rs + 0 {
        renderTextRow(displayId, glyphs, cells, row)
        row = row + 1
    }
    display::present(displayId)
}

fun renderRows(displayId: Int, glyphs: Array<Long>, cells: String, startRow: Int, endRow: Int) {
    var row: Int = startRow
    if (row < 0) {
        row = 0
    }
    var last: Int = endRow
    val rs: Int = rows(displayId)
    if (last >= rs) {
        last = rs - 1
    }
    while row < last + 1 {
        renderTextRow(displayId, glyphs, cells, row)
        row = row + 1
    }
    display::present(displayId)
}

fun renderAutoscrolledRows(displayId: Int, buffer: TerminalBuffer, startRow: Int) {
    var row: Int = startRow
    if (row < 0) {
        row = 0
    }
    val rs: Int = rows(displayId)
    if (row >= rs) {
        row = rs - 1
    }
    while row < rs + 0 {
        renderTextRow(displayId, buffer.glyphs, buffer.cellsText, row)
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
    return cells + strings::repeat(" ", cols * count)
}

fun historyRowStart(historyRows: Int, displayRows: Int, viewportOffset: Int): Int {
    var start: Int = historyRows - displayRows - viewportOffset
    if (start < 0) {
        start = 0
    }
    return start
}

fun viewportCells(historyCells: String, historyRows: Int, cols: Int, rs: Int, viewportOffset: Int): String {
    val startRow: Int = historyRowStart(historyRows, rs, viewportOffset)
    if (viewportOffset == 0 && historyRows == rs) {
        return historyCells
    }
    if (startRow >= 0 && startRow + rs <= historyRows) {
        return strings::slice(historyCells, startRow * cols, (startRow + rs) * cols)
    }
    var result: String = ""
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

fun viewportRowCells(buffer: TerminalBuffer, row: Int): String {
    val cols: Int = buffer.displayColumns
    val sourceRow: Int = historyRowStart(buffer.historyRows, buffer.displayRows, buffer.viewportOffset) + row
    if (sourceRow >= 0 && sourceRow < buffer.historyRows) {
        return strings::slice(buffer.historyCells, sourceRow * cols, (sourceRow + 1) * cols)
    }
    return blankCells(cols)
}

fun renderViewportRow(displayId: Int, buffer: TerminalBuffer, row: Int) {
    renderRowCells(displayId, buffer.glyphs, row, viewportRowCells(buffer, row))
}

fun renderViewport(displayId: Int, buffer: TerminalBuffer) {
    if (buffer.viewportOffset == 0) {
        renderAllRows(displayId, buffer.glyphs, buffer.cellsText)
        return
    }
    renderAllRows(
        displayId,
        buffer.glyphs,
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
        glyphs = buffer.glyphs,
        cursorRow = buffer.cursorRow,
        cursorColumn = buffer.cursorColumn,
        displayColumns = buffer.displayColumns,
        displayRows = buffer.displayRows,
        historyRows = buffer.historyRows,
        viewportOffset = nextOffset
    )
    val actualDelta: Int = nextOffset - buffer.viewportOffset
    if (actualDelta == 0) {
        return updated
    }
    val cols: Int = columns(displayId)
    val rs: Int = rows(displayId)
    if (rs > 1 && actualDelta == 1) {
        display::copyRect(displayId, 0, 0, cols * 6, (rs - 1) * 9, 0, 9)
        renderViewportRow(displayId, updated, 0)
        display::present(displayId)
        return updated
    }
    if (rs > 1 && actualDelta == 0 - 1) {
        display::copyRect(displayId, 0, 9, cols * 6, (rs - 1) * 9, 0, 0)
        renderViewportRow(displayId, updated, rs - 1)
        display::present(displayId)
        return updated
    }
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
        glyphs = buffer.glyphs,
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
    return strings::slice(cells, cols, strings::length(cells)) + blankCells(cols)
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
    val startVisibleRow: Int = row - historyRowStart(buffer.historyRows, rs, 0)
    var scrolled: Bool = false
    var scrolls: Int = 0
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
                scrolled = true
                scrolls = scrolls + 1
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
        glyphs = buffer.glyphs,
        cursorRow = row,
        cursorColumn = col,
        displayColumns = cols,
        displayRows = rs,
        historyRows = historyRows,
        viewportOffset = buffer.viewportOffset
    )
    if (buffer.viewportOffset == 0) {
        if (scrolled) {
            var startRow: Int = startVisibleRow - scrolls
            renderAutoscrolledRows(displayId, updated, startRow)
        } else {
            val endVisibleRow: Int = row - historyRowStart(historyRows, rs, 0)
            renderRows(displayId, buffer.glyphs, cells, startVisibleRow, endVisibleRow)
        }
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
            renderTextRow(displayId, buffer.glyphs, visibleCells, row)
        }
        rowOffset = rowOffset + 1
    }
}

fun startsWithText(text: String, prefix: String): Bool {
    if (strings::length(prefix) > strings::length(text)) {
        return false
    }
    var i: Int = 0
    while i < strings::length(prefix) {
        if (strings::charAt(text, i) != strings::charAt(prefix, i)) {
            return false
        }
        i = i + 1
    }
    return true
}

fun renderInputLineAppend(displayId: Int, buffer: TerminalBuffer, line: String, startIndex: Int) {
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
    var i: Int = 0
    var run: String = ""
    var runX: Int = x
    var runY: Int = y
    while i < startIndex + 0 {
        x = x + 1
        if (x >= cols) {
            x = 0
            y = y + 1
            if (y >= rs) {
                return
            }
        }
        i = i + 1
    }
    i = startIndex
    while i < strings::length(line) {
        if (x >= cols) {
            drawGlyphRun(displayId, runX, runY, run, 2016)
            run = ""
            x = 0
            y = y + 1
            if (y >= rs) {
                display::present(displayId)
                return
            }
        }
        val ch: String = strings::charAt(line, i)
        if (ch == " ") {
            drawGlyphRun(displayId, runX, runY, run, 2016)
            run = ""
            drawGlyph(displayId, buffer.glyphs, x, y, ch, 2016)
        } else {
            if (run == "") {
                runX = x
                runY = y
            }
            run = run + ch
        }
        x = x + 1
        i = i + 1
    }
    drawGlyphRun(displayId, runX, runY, run, 2016)
    display::present(displayId)
}

fun restoreInputCell(displayId: Int, buffer: TerminalBuffer, lineIndex: Int) {
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
    var i: Int = 0
    while i < lineIndex + 0 {
        x = x + 1
        if (x >= cols) {
            x = 0
            y = y + 1
            if (y >= rs) {
                return
            }
        }
        i = i + 1
    }
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
    drawGlyph(displayId, buffer.glyphs, x, y, cellAt(visibleCells, y * cols + x), 2016)
    display::present(displayId)
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
    if (strings::length(previousLine) == strings::length(line) + 1 && startsWithText(previousLine, line)) {
        restoreInputCell(displayId, buffer, strings::length(line))
        return
    }
    if (strings::length(previousLine) < strings::length(line) && startsWithText(line, previousLine)) {
        renderInputLineAppend(displayId, buffer, line, strings::length(previousLine))
        return
    }
    clearRenderedInputLine(displayId, buffer, previousLine)
    var i: Int = 0
    var run: String = ""
    var runX: Int = x
    var runY: Int = y
    while i < strings::length(line) {
        if (y >= rs) {
            drawGlyphRun(displayId, runX, runY, run, 2016)
            display::present(displayId)
            return
        }
        if (x >= cols) {
            drawGlyphRun(displayId, runX, runY, run, 2016)
            run = ""
            x = 0
            y = y + 1
            if (y >= rs) {
                display::present(displayId)
                return
            }
        }
        val ch: String = strings::charAt(line, i)
        if (ch == " ") {
            drawGlyphRun(displayId, runX, runY, run, 2016)
            run = ""
            drawGlyph(displayId, buffer.glyphs, x, y, ch, 2016)
        } else {
            if (run == "") {
                runX = x
                runY = y
            }
            run = run + ch
        }
        x = x + 1
        i = i + 1
    }
    drawGlyphRun(displayId, runX, runY, run, 2016)
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

fun eventResult(displayId: Int, buffer: TerminalBuffer, line: String, renderedLine: String, renderInput: Bool): TerminalEventResult {
    return TerminalEventResult(
        displayId = displayId,
        buffer = buffer,
        line = line,
        renderedLine = renderedLine,
        renderInput = renderInput
    )
}

fun handleTerminalEvent(input: Int, displayId: Int, buffer: TerminalBuffer, line: String, renderedLine: String, event: Event): TerminalEventResult {
    if (event.name == "display_attach" || event.name == "display_resize") {
        val nextDisplayId: Int = display::primary()
        if (buffer.displayColumns == columns(nextDisplayId) && buffer.displayRows == rows(nextDisplayId)) {
            renderViewport(nextDisplayId, buffer)
            if (buffer.viewportOffset == 0 && line != "") {
                renderInputLine(nextDisplayId, buffer, renderedLine, line)
                return eventResult(nextDisplayId, buffer, line, line, false)
            }
            return eventResult(nextDisplayId, buffer, line, renderedLine, false)
        }
        display::clear(nextDisplayId, 0)
        display::present(nextDisplayId)
        return eventResult(nextDisplayId, newTerminalBuffer(nextDisplayId), "", "", false)
    }
    if (event.name == "char" || event.name == "paste") {
        val typed: String = events::argString(event, 0)
        if (typed != "") {
            return eventResult(displayId, followBottom(displayId, buffer), line + typed, renderedLine, true)
        }
        return eventResult(displayId, buffer, line, renderedLine, false)
    }
    if (event.name == "key") {
        val key: Int = events::argInt(event, 0)
        if (key == 257 || key == 335) {
            if (line != "" && buffer.viewportOffset == 0) {
                clearRenderedInputLine(displayId, buffer, line)
            }
            val nextBuffer: TerminalBuffer = followBottom(displayId, buffer)
            val committedBuffer: TerminalBuffer = appendText(displayId, nextBuffer, line + "\n")
            ipc::write(input, line + "\n")
            return eventResult(displayId, committedBuffer, "", "", false)
        }
        if (key == 259) {
            if (line != "") {
                return eventResult(displayId, followBottom(displayId, buffer), dropLast(line), renderedLine, true)
            }
            return eventResult(displayId, buffer, line, renderedLine, false)
        }
        if (key == 266) {
            var pageRows: Int = rows(displayId) - 1
            if (pageRows <= 0) {
                pageRows = 1
            }
            return eventResult(displayId, scrollViewportBy(displayId, buffer, pageRows), line, renderedLine, false)
        }
        if (key == 265) {
            return eventResult(displayId, scrollViewportBy(displayId, buffer, 1), line, renderedLine, false)
        }
        if (key == 264) {
            val nextBuffer: TerminalBuffer = scrollViewportBy(displayId, buffer, 0 - 1)
            if (nextBuffer.viewportOffset == 0 && line != "") {
                renderInputLine(displayId, nextBuffer, renderedLine, line)
                return eventResult(displayId, nextBuffer, line, line, false)
            }
            return eventResult(displayId, nextBuffer, line, renderedLine, false)
        }
        if (key == 267) {
            var pageRows: Int = rows(displayId) - 1
            if (pageRows <= 0) {
                pageRows = 1
            }
            val nextBuffer: TerminalBuffer = scrollViewportBy(displayId, buffer, 0 - pageRows)
            if (nextBuffer.viewportOffset == 0 && line != "") {
                renderInputLine(displayId, nextBuffer, renderedLine, line)
                return eventResult(displayId, nextBuffer, line, line, false)
            }
            return eventResult(displayId, nextBuffer, line, renderedLine, false)
        }
    }
    return eventResult(displayId, buffer, line, renderedLine, false)
}

fun drainInputBatch(input: Int, displayId: Int, buffer: TerminalBuffer, line: String, renderedLine: String, renderInput: Bool): TerminalEventResult {
    var result: TerminalEventResult = eventResult(displayId, buffer, line, renderedLine, renderInput)
    var count: Int = 0
    while count < inputBatchLimit() {
        val event: Event = events::tryPull()
        if (event.name == "") {
            return result
        }
        val next: TerminalEventResult =
            handleTerminalEvent(input, result.displayId, result.buffer, result.line, result.renderedLine, event)
        result =
            eventResult(
                next.displayId,
                next.buffer,
                next.line,
                next.renderedLine,
                result.renderInput || next.renderInput
            )
        count = count + 1
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
            val first: TerminalEventResult = handleTerminalEvent(input, displayId, buffer, line, renderedLine, event)
            val batch: TerminalEventResult =
                drainInputBatch(input, first.displayId, first.buffer, first.line, first.renderedLine, first.renderInput)
            displayId = batch.displayId
            buffer = batch.buffer
            line = batch.line
            renderedLine = batch.renderedLine
            if (batch.renderInput && buffer.viewportOffset == 0) {
                renderInputLine(displayId, buffer, renderedLine, line)
                renderedLine = line
            }
        }
    }
}
