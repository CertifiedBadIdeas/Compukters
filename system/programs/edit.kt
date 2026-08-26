/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package compukter.system.edit

import compukter.filesystem.FileSystem
import compukter.terminal.Terminal

fun main(args: Array<String>) {
    if (args.size != 1) {
        Terminal.write("usage: edit <path>\n")
        return
    }
    val commandLine = args[0]
    val path = if (commandLine[0] == '/') commandLine else "/home/" + commandLine
    val kind = FileSystem.stat(path)
    if (kind == 2) {
        Terminal.write("edit: is a directory: " + path + "\n")
        return
    }
    if (kind != 1 && kind != 0 - 2) {
        Terminal.write("edit: " + editFileSystemError(kind) + "\n")
        return
    }

    val source = if (kind == 1) FileSystem.readText(path) else ""
    val buffer = CharArray(4096)
    var state = editInsertDocument(buffer, editEmpty(buffer), source)
    if (state < 0) {
        Terminal.write("edit: file exceeds 4096 UTF-16 units\n")
        return
    }
    var dirty = editContainsCrLf(source)
    var status = if (dirty) "CRLF normalized" else ""
    var rowOffset = 0
    var columnOffset = 0
    var confirmExit = false
    var running = true
    val rowBuffer = CharArray(102)

    while (running) {
        val cursorLine = editCursorLine(buffer, state)
        val cursorColumn = editCursorColumn(buffer, state)
        rowOffset = editAdjustRowOffset(cursorLine, rowOffset, 16)
        columnOffset = editAdjustColumnOffset(cursorColumn, columnOffset, 51)
        editRender(
            buffer,
            state,
            path,
            dirty,
            status,
            confirmExit,
            rowOffset,
            columnOffset,
            rowBuffer,
        )
        status = ""

        val event = Terminal.awaitEvent()
        if (event == 1) {
            val text = Terminal.eventText()
            if (confirmExit) {
                if (editStartsWith(text, 'y', 'Y')) {
                    val saved = editSave(buffer, state, path)
                    state = editState(editLogicalLength(buffer, state), buffer.size)
                    if (saved == 0) running = false else status = editFileSystemError(saved)
                    if (saved == 0) dirty = false
                    confirmExit = saved != 0
                } else if (editStartsWith(text, 'n', 'N')) {
                    running = false
                }
            } else {
                val next = editInsertInput(buffer, state, text)
                if (next == state && text != "") status = "Buffer full"
                else if (next != state) {
                    state = next
                    dirty = true
                }
            }
        } else if (event == 2) {
            val key = Terminal.eventKey()
            val action = Terminal.eventAction()
            val modifiers = Terminal.eventModifiers()
            if (confirmExit) {
                if (key == 1 && action == 1) confirmExit = false
            } else if ((action == 1 || action == 2) && editHasControl(modifiers) && key == 83) {
                val saved = editSave(buffer, state, path)
                val length = editLogicalLength(buffer, state)
                state = editState(length, buffer.size)
                if (saved == 0) {
                    dirty = false
                    status = "Saved"
                } else {
                    status = editFileSystemError(saved)
                }
            } else if ((action == 1 || action == 2) && editHasControl(modifiers) && key == 88) {
                if (dirty) confirmExit = true else running = false
            } else if (action == 1 || action == 2) {
                val previous = state
                if (key == 8) state = editBackspace(buffer, state)
                else if (key == 9) state = editInsertTab(buffer, state)
                else if (key == 13) state = editInsertNewline(buffer, state)
                else if (key == 257) state = editDelete(buffer, state)
                else if (key == 258) state = editMoveHome(buffer, state)
                else if (key == 259) state = editMoveEnd(buffer, state)
                else if (key == 260) state = editMoveVertical(buffer, state, 0 - 16)
                else if (key == 261) state = editMoveVertical(buffer, state, 16)
                else if (key == 262) state = editMoveVertical(buffer, state, 0 - 1)
                else if (key == 263) state = editMoveLeft(buffer, state)
                else if (key == 264) state = editMoveVertical(buffer, state, 1)
                else if (key == 265) state = editMoveRight(buffer, state)
                if (state != previous && (key == 8 || key == 9 || key == 13 || key == 257)) dirty = true
                if (state == previous && (key == 9 || key == 13)) status = "Buffer full"
            }
        }
        Terminal.finishEvent()
    }
}

fun editEmpty(buffer: CharArray): Int = editState(0, buffer.size)

fun editGapStart(state: Int): Int = state / 65536

fun editGapEnd(state: Int): Int = state % 65536

private fun editState(
    gapStart: Int,
    gapEnd: Int,
): Int = gapStart * 65536 + gapEnd

fun editInsertText(
    buffer: CharArray,
    state: Int,
    text: String,
): Int {
    var gapStart = editGapStart(state)
    val gapEnd = editGapEnd(state)
    if (text.length > gapEnd - gapStart) return state
    var index = 0
    while (index < text.length) {
        buffer[gapStart] = text[index]
        gapStart = gapStart + 1
        index = index + 1
    }
    return editState(gapStart, gapEnd)
}

fun editMoveLeft(
    buffer: CharArray,
    state: Int,
): Int {
    val gapStart = editGapStart(state)
    val gapEnd = editGapEnd(state)
    if (gapStart == 0) return state
    val width = editScalarBefore(buffer, gapStart)
    var index = 0
    while (index < width) {
        buffer[gapEnd - width + index] = buffer[gapStart - width + index]
        index = index + 1
    }
    return editState(gapStart - width, gapEnd - width)
}

fun editMoveRight(
    buffer: CharArray,
    state: Int,
): Int {
    val gapStart = editGapStart(state)
    val gapEnd = editGapEnd(state)
    if (gapEnd == buffer.size) return state
    val width = editScalarAt(buffer, gapEnd, buffer.size)
    var index = 0
    while (index < width) {
        buffer[gapStart + index] = buffer[gapEnd + index]
        index = index + 1
    }
    return editState(gapStart + width, gapEnd + width)
}

fun editBackspace(
    buffer: CharArray,
    state: Int,
): Int {
    val gapStart = editGapStart(state)
    if (gapStart == 0) return state
    return editState(gapStart - editScalarBefore(buffer, gapStart), editGapEnd(state))
}

fun editDelete(
    buffer: CharArray,
    state: Int,
): Int {
    val gapEnd = editGapEnd(state)
    if (gapEnd == buffer.size) return state
    return editState(editGapStart(state), gapEnd + editScalarAt(buffer, gapEnd, buffer.size))
}

fun editInsertTab(
    buffer: CharArray,
    state: Int,
): Int = editInsertText(buffer, state, "    ")

fun editInsertNewline(
    buffer: CharArray,
    state: Int,
): Int {
    val gapStart = editGapStart(state)
    var lineStart = gapStart
    while (lineStart > 0 && buffer[lineStart - 1] != '\n') lineStart = lineStart - 1
    var indentationEnd = lineStart
    while (
        indentationEnd < gapStart &&
        (buffer[indentationEnd] == ' ' || buffer[indentationEnd] == '\t')
    ) {
        indentationEnd = indentationEnd + 1
    }
    val indentation = indentationEnd - lineStart
    if (editGapEnd(state) - gapStart < indentation + 1) return state
    var next = editInsertText(buffer, state, "\n")
    var index = lineStart
    while (index < indentationEnd) {
        next = editInsertCharacter(buffer, next, buffer[index])
        index = index + 1
    }
    return next
}

fun editCursorLine(
    buffer: CharArray,
    state: Int,
): Int {
    var line = 0
    var index = 0
    val gapStart = editGapStart(state)
    while (index < gapStart) {
        if (buffer[index] == '\n') line = line + 1
        index = index + 1
    }
    return line
}

fun editCursorColumn(
    buffer: CharArray,
    state: Int,
): Int {
    var lineStart = editGapStart(state)
    while (lineStart > 0 && buffer[lineStart - 1] != '\n') lineStart = lineStart - 1
    var column = 0
    var index = lineStart
    val gapStart = editGapStart(state)
    while (index < gapStart) {
        index = index + editScalarAt(buffer, index, gapStart)
        column = column + 1
    }
    return column
}

fun editAdjustRowOffset(
    cursorLine: Int,
    rowOffset: Int,
    height: Int,
): Int {
    if (cursorLine < rowOffset) return cursorLine
    if (cursorLine >= rowOffset + height) return cursorLine - height + 1
    return rowOffset
}

fun editAdjustColumnOffset(
    cursorColumn: Int,
    columnOffset: Int,
    width: Int,
): Int {
    if (cursorColumn < columnOffset) return cursorColumn
    if (cursorColumn >= columnOffset + width) return cursorColumn - width + 1
    return columnOffset
}

fun editCompact(
    buffer: CharArray,
    state: Int,
): Int {
    val gapStart = editGapStart(state)
    val gapEnd = editGapEnd(state)
    var source = gapEnd
    var destination = gapStart
    while (source < buffer.size) {
        buffer[destination] = buffer[source]
        source = source + 1
        destination = destination + 1
    }
    return destination
}

fun editText(
    buffer: CharArray,
    state: Int,
): String =
    buffer.concatToString(0, editGapStart(state)) +
        buffer.concatToString(editGapEnd(state), buffer.size)

fun editLogicalLength(
    buffer: CharArray,
    state: Int,
): Int = editGapStart(state) + buffer.size - editGapEnd(state)

fun editMoveHome(
    buffer: CharArray,
    state: Int,
): Int {
    var target = editGapStart(state)
    while (target > 0 && editLogicalCharAt(buffer, state, target - 1) != '\n') target = target - 1
    return editMoveTo(buffer, state, target)
}

fun editMoveEnd(
    buffer: CharArray,
    state: Int,
): Int {
    var target = editGapStart(state)
    val length = editLogicalLength(buffer, state)
    while (target < length && editLogicalCharAt(buffer, state, target) != '\n') target = target + 1
    return editMoveTo(buffer, state, target)
}

fun editMoveVertical(
    buffer: CharArray,
    state: Int,
    delta: Int,
): Int {
    val column = editCursorColumn(buffer, state)
    val currentLine = editCursorLine(buffer, state)
    var targetLine = currentLine + delta
    if (targetLine < 0) targetLine = 0
    val lastLine = editLineCount(buffer, state) - 1
    if (targetLine > lastLine) targetLine = lastLine
    var target = editLineStart(buffer, state, targetLine)
    val length = editLogicalLength(buffer, state)
    var currentColumn = 0
    while (
        target < length &&
        editLogicalCharAt(buffer, state, target) != '\n' &&
        currentColumn < column
    ) {
        target = target + editLogicalScalarWidth(buffer, state, target)
        currentColumn = currentColumn + 1
    }
    return editMoveTo(buffer, state, target)
}

private fun editMoveTo(
    buffer: CharArray,
    initialState: Int,
    target: Int,
): Int {
    var state = initialState
    while (editGapStart(state) > target) state = editMoveLeft(buffer, state)
    while (editGapStart(state) < target) state = editMoveRight(buffer, state)
    return state
}

private fun editLineCount(
    buffer: CharArray,
    state: Int,
): Int {
    var count = 1
    var index = 0
    val length = editLogicalLength(buffer, state)
    while (index < length) {
        if (editLogicalCharAt(buffer, state, index) == '\n') count = count + 1
        index = index + 1
    }
    return count
}

private fun editLineStart(
    buffer: CharArray,
    state: Int,
    requestedLine: Int,
): Int {
    var line = 0
    var index = 0
    val length = editLogicalLength(buffer, state)
    while (index < length && line < requestedLine) {
        if (editLogicalCharAt(buffer, state, index) == '\n') line = line + 1
        index = index + 1
    }
    return index
}

private fun editLogicalCharAt(
    buffer: CharArray,
    state: Int,
    index: Int,
): Char {
    val gapStart = editGapStart(state)
    return if (index < gapStart) buffer[index] else buffer[editGapEnd(state) + index - gapStart]
}

private fun editLogicalScalarWidth(
    buffer: CharArray,
    state: Int,
    index: Int,
): Int {
    val length = editLogicalLength(buffer, state)
    if (
        index + 1 < length &&
        editHighSurrogate(editLogicalCharAt(buffer, state, index)) &&
        editLowSurrogate(editLogicalCharAt(buffer, state, index + 1))
    ) {
        return 2
    }
    return 1
}

private fun editInsertCharacter(
    buffer: CharArray,
    state: Int,
    character: Char,
): Int {
    val gapStart = editGapStart(state)
    val gapEnd = editGapEnd(state)
    if (gapStart == gapEnd) return state
    buffer[gapStart] = character
    return editState(gapStart + 1, gapEnd)
}

private fun editInsertDocument(
    buffer: CharArray,
    state: Int,
    text: String,
): Int {
    var outputLength = 0
    var index = 0
    while (index < text.length) {
        if (text[index] != '\r' || index + 1 >= text.length || text[index + 1] != '\n') {
            outputLength = outputLength + 1
        }
        index = index + 1
    }
    if (outputLength > editGapEnd(state) - editGapStart(state)) return 0 - 1
    var next = state
    index = 0
    while (index < text.length) {
        if (text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
            next = editInsertCharacter(buffer, next, '\n')
            index = index + 2
        } else {
            next = editInsertCharacter(buffer, next, text[index])
            index = index + 1
        }
    }
    return next
}

private fun editInsertInput(
    buffer: CharArray,
    state: Int,
    text: String,
): Int {
    var accepted = 0
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\n' || character == '\t' || (character >= ' ' && character != '\u007f')) {
            accepted = accepted + 1
        }
        index = index + 1
    }
    if (accepted > editGapEnd(state) - editGapStart(state)) return state
    var next = state
    index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\n') next = editInsertNewline(buffer, next)
        else if (character == '\t') next = editInsertTab(buffer, next)
        else if (character >= ' ' && character != '\u007f') next = editInsertCharacter(buffer, next, character)
        index = index + 1
    }
    return next
}

private fun editRender(
    buffer: CharArray,
    state: Int,
    path: String,
    dirty: Boolean,
    status: String,
    confirmExit: Boolean,
    rowOffset: Int,
    columnOffset: Int,
    rowBuffer: CharArray,
) {
    Terminal.setColors(15, 0)
    Terminal.fill(0, 0, 51, 19, ' ')
    Terminal.writeAt(0, 0, "Compukters edit  " + path + if (dirty) " *" else "")
    var row = 0
    while (row < 16) {
        val text = editVisibleLine(buffer, state, rowOffset + row, columnOffset, rowBuffer)
        if (text != "") Terminal.writeAt(0, row + 1, text)
        row = row + 1
    }
    val cursorLine = editCursorLine(buffer, state)
    val cursorColumn = editCursorColumn(buffer, state)
    var message = status
    if (confirmExit) message = "Save modified buffer? Y/N"
    else if (message == "") message = "Ln " + editNumber(cursorLine + 1) + ", Col " + editNumber(cursorColumn + 1)
    Terminal.writeAt(0, 17, message)
    Terminal.writeAt(0, 18, "^S Save  ^X Exit")
    Terminal.setCursor(cursorColumn - columnOffset, cursorLine - rowOffset + 1)
    Terminal.setCursorVisible(!confirmExit)
}

private fun editVisibleLine(
    buffer: CharArray,
    state: Int,
    line: Int,
    columnOffset: Int,
    rowBuffer: CharArray,
): String {
    var index = editLineStart(buffer, state, line)
    val length = editLogicalLength(buffer, state)
    var skipped = 0
    while (index < length && editLogicalCharAt(buffer, state, index) != '\n' && skipped < columnOffset) {
        index = index + editLogicalScalarWidth(buffer, state, index)
        skipped = skipped + 1
    }
    var cells = 0
    var units = 0
    while (index < length && editLogicalCharAt(buffer, state, index) != '\n' && cells < 51) {
        val width = editLogicalScalarWidth(buffer, state, index)
        var unit = 0
        while (unit < width) {
            rowBuffer[units] = editLogicalCharAt(buffer, state, index + unit)
            units = units + 1
            unit = unit + 1
        }
        index = index + width
        cells = cells + 1
    }
    return rowBuffer.concatToString(0, units)
}

private fun editSave(
    buffer: CharArray,
    state: Int,
    path: String,
): Int {
    val length = editCompact(buffer, state)
    return FileSystem.writeText(path, buffer.concatToString(0, length))
}

private fun editNumber(value: Int): String {
    if (value < 10) return editDigit(value)
    if (value < 100) return editDigit(value / 10) + editDigit(value % 10)
    if (value < 1000) return editDigit(value / 100) + editDigit((value / 10) % 10) + editDigit(value % 10)
    return editDigit(value / 1000) + editDigit((value / 100) % 10) + editDigit((value / 10) % 10) + editDigit(value % 10)
}

private fun editDigit(value: Int): String {
    if (value == 0) return "0"
    if (value == 1) return "1"
    if (value == 2) return "2"
    if (value == 3) return "3"
    if (value == 4) return "4"
    if (value == 5) return "5"
    if (value == 6) return "6"
    if (value == 7) return "7"
    if (value == 8) return "8"
    return "9"
}

private fun editFileSystemError(result: Int): String {
    if (result == 0 - 1) return "invalid path"
    if (result == 0 - 2) return "not found"
    if (result == 0 - 5) return "is a directory"
    if (result == 0 - 7) return "read-only filesystem"
    if (result == 0 - 8) return "permission denied"
    if (result == 0 - 10) return "filesystem quota exceeded"
    if (result == 0 - 11) return "filesystem busy"
    if (result == 0 - 12) return "filesystem unavailable"
    if (result == 0 - 13) return "filesystem closed"
    return "filesystem error"
}

private fun editContainsSpace(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        if (value[index] == ' ') return true
        index = index + 1
    }
    return false
}

private fun editContainsCrLf(value: String): Boolean {
    var index = 0
    while (index + 1 < value.length) {
        if (value[index] == '\r' && value[index + 1] == '\n') return true
        index = index + 1
    }
    return false
}

private fun editStartsWith(
    value: String,
    lower: Char,
    upper: Char,
): Boolean = value != "" && (value[0] == lower || value[0] == upper)

private fun editHasControl(modifiers: Int): Boolean = modifiers % 4 >= 2

private fun editScalarBefore(
    buffer: CharArray,
    end: Int,
): Int {
    if (end >= 2 && editLowSurrogate(buffer[end - 1]) && editHighSurrogate(buffer[end - 2])) return 2
    return 1
}

private fun editScalarAt(
    buffer: CharArray,
    start: Int,
    end: Int,
): Int {
    if (start + 1 < end && editHighSurrogate(buffer[start]) && editLowSurrogate(buffer[start + 1])) return 2
    return 1
}

private fun editHighSurrogate(value: Char): Boolean = value >= '\uD800' && value <= '\uDBFF'

private fun editLowSurrogate(value: Char): Boolean = value >= '\uDC00' && value <= '\uDFFF'
