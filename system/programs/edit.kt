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
import compukter.io.Stderr
import compukter.terminal.Terminal
import kotlin.collections.copyInto

value class EditState(
    val gapStart: Int,
    val gapEnd: Int,
)

fun main(args: Array<String>) {
    val argumentError = editError(args)
    if (argumentError.isNotEmpty()) {
        Stderr.write(argumentError + "\n")
        return
    }
    val path = editPath(args)
    val kind = FileSystem.stat(path)
    if (kind == 2) {
        Stderr.write("edit: is a directory: " + path + "\n")
        return
    }
    if (kind != 1 && kind != 0 - 2) {
        Stderr.write("edit: " + editFileSystemError(kind) + "\n")
        return
    }

    val source = if (kind == 1) FileSystem.readText(path) else ""
    val buffer = CharArray(4096)
    val initialState = editInsertDocument(buffer, editEmpty(buffer), source)
    if (initialState == null) {
        Stderr.write("edit: file exceeds 4096 UTF-16 units\n")
        return
    }
    var state: EditState = initialState
    var dirty = source.contains("\r\n")
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
                    if (saved == 0) running = false else status = editFileSystemError(saved)
                    if (saved == 0) dirty = false
                    confirmExit = saved != 0
                } else if (editStartsWith(text, 'n', 'N')) {
                    running = false
                }
            } else {
                val next = editInsertInput(buffer, state, text)
                if (next == state && text.isNotEmpty()) {
                    status = "Buffer full"
                } else if (next != state) {
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
                state =
                    when (key) {
                        8 -> editBackspace(buffer, state)
                        9 -> editInsertTab(buffer, state)
                        13 -> editInsertNewline(buffer, state)
                        257 -> editDelete(buffer, state)
                        258 -> editMoveHome(buffer, state)
                        259 -> editMoveEnd(buffer, state)
                        260 -> editMoveVertical(buffer, state, -16)
                        261 -> editMoveVertical(buffer, state, 16)
                        262 -> editMoveVertical(buffer, state, -1)
                        263 -> editMoveLeft(buffer, state)
                        264 -> editMoveVertical(buffer, state, 1)
                        265 -> editMoveRight(buffer, state)
                        else -> state
                    }
                if (state != previous && (key == 8 || key == 9 || key == 13 || key == 257)) dirty = true
                if (state == previous && (key == 9 || key == 13)) status = "Buffer full"
            }
        }
        Terminal.finishEvent()
    }
}

fun editError(args: Array<String>): String = if (args.size == 1) "" else "usage: edit <path>"

fun editPath(args: Array<String>): String {
    val path = args[0]
    return if (path.startsWith("/")) path else "/home/" + path
}

fun editEmpty(buffer: CharArray): EditState = EditState(0, buffer.size)

fun editInsertText(
    buffer: CharArray,
    state: EditState,
    text: String,
): EditState {
    var gapStart = state.gapStart
    val gapEnd = state.gapEnd
    if (text.length > gapEnd - gapStart) return state
    for (index in 0 until text.length) {
        buffer[gapStart] = text[index]
        gapStart = gapStart + 1
    }
    return EditState(gapStart, gapEnd)
}

fun editMoveLeft(
    buffer: CharArray,
    state: EditState,
): EditState {
    val gapStart = state.gapStart
    val gapEnd = state.gapEnd
    if (gapStart == 0) return state
    val width = editScalarBefore(buffer, gapStart)
    buffer.copyInto(buffer, gapEnd - width, gapStart - width, gapStart)
    return EditState(gapStart - width, gapEnd - width)
}

fun editMoveRight(
    buffer: CharArray,
    state: EditState,
): EditState {
    val gapStart = state.gapStart
    val gapEnd = state.gapEnd
    if (gapEnd == buffer.size) return state
    val width = editScalarAt(buffer, gapEnd, buffer.size)
    buffer.copyInto(buffer, gapStart, gapEnd, gapEnd + width)
    return EditState(gapStart + width, gapEnd + width)
}

fun editBackspace(
    buffer: CharArray,
    state: EditState,
): EditState {
    val gapStart = state.gapStart
    if (gapStart == 0) return state
    return EditState(gapStart - editScalarBefore(buffer, gapStart), state.gapEnd)
}

fun editDelete(
    buffer: CharArray,
    state: EditState,
): EditState {
    val gapEnd = state.gapEnd
    if (gapEnd == buffer.size) return state
    return EditState(state.gapStart, gapEnd + editScalarAt(buffer, gapEnd, buffer.size))
}

fun editInsertTab(
    buffer: CharArray,
    state: EditState,
): EditState = editInsertText(buffer, state, "    ")

fun editInsertNewline(
    buffer: CharArray,
    state: EditState,
): EditState {
    val gapStart = state.gapStart
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
    if (state.gapEnd - gapStart < indentation + 1) return state
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
    state: EditState,
): Int {
    var line = 0
    var index = 0
    val gapStart = state.gapStart
    while (index < gapStart) {
        if (buffer[index] == '\n') line = line + 1
        index = index + 1
    }
    return line
}

fun editCursorColumn(
    buffer: CharArray,
    state: EditState,
): Int {
    var lineStart = state.gapStart
    while (lineStart > 0 && buffer[lineStart - 1] != '\n') lineStart = lineStart - 1
    var column = 0
    var index = lineStart
    val gapStart = state.gapStart
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

fun editText(
    buffer: CharArray,
    state: EditState,
): String {
    val text = CharArray(editLogicalLength(buffer, state))
    buffer.copyInto(text, 0, 0, state.gapStart)
    buffer.copyInto(text, state.gapStart, state.gapEnd, buffer.size)
    return text.concatToString(0, text.size)
}

fun editLogicalLength(
    buffer: CharArray,
    state: EditState,
): Int = state.gapStart + buffer.size - state.gapEnd

fun editMoveHome(
    buffer: CharArray,
    state: EditState,
): EditState {
    var target = state.gapStart
    while (target > 0 && editLogicalCharAt(buffer, state, target - 1) != '\n') target = target - 1
    return editMoveTo(buffer, state, target)
}

fun editMoveEnd(
    buffer: CharArray,
    state: EditState,
): EditState {
    var target = state.gapStart
    val length = editLogicalLength(buffer, state)
    while (target < length && editLogicalCharAt(buffer, state, target) != '\n') target = target + 1
    return editMoveTo(buffer, state, target)
}

fun editMoveVertical(
    buffer: CharArray,
    state: EditState,
    delta: Int,
): EditState {
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
    initialState: EditState,
    target: Int,
): EditState {
    var state = initialState
    while (state.gapStart > target) state = editMoveLeft(buffer, state)
    while (state.gapStart < target) state = editMoveRight(buffer, state)
    return state
}

private fun editLineCount(
    buffer: CharArray,
    state: EditState,
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
    state: EditState,
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
    state: EditState,
    index: Int,
): Char {
    val gapStart = state.gapStart
    return if (index < gapStart) buffer[index] else buffer[state.gapEnd + index - gapStart]
}

private fun editLogicalScalarWidth(
    buffer: CharArray,
    state: EditState,
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
    state: EditState,
    character: Char,
): EditState {
    val gapStart = state.gapStart
    val gapEnd = state.gapEnd
    if (gapStart == gapEnd) return state
    buffer[gapStart] = character
    return EditState(gapStart + 1, gapEnd)
}

private fun editInsertDocument(
    buffer: CharArray,
    state: EditState,
    text: String,
): EditState? {
    var outputLength = 0
    var index = 0
    while (index < text.length) {
        if (text[index] != '\r' || index + 1 >= text.length || text[index + 1] != '\n') {
            outputLength = outputLength + 1
        }
        index = index + 1
    }
    if (outputLength > state.gapEnd - state.gapStart) return null
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
    state: EditState,
    text: String,
): EditState {
    // Preflight the expanded paste before changing any buffer contents.
    var lineStart = state.gapStart
    while (lineStart > 0 && buffer[lineStart - 1] != '\n') lineStart = lineStart - 1
    var indentationEnd = lineStart
    while (indentationEnd < state.gapStart && (buffer[indentationEnd] == ' ' || buffer[indentationEnd] == '\t')) {
        indentationEnd = indentationEnd + 1
    }
    var indentation = indentationEnd - lineStart
    var inIndentation = indentationEnd == state.gapStart
    var required = 0
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\n') {
            required = required + 1 + indentation
            inIndentation = true
        } else if (character == '\t') {
            required = required + 4
            if (inIndentation) indentation = indentation + 4
        } else if (character >= ' ' && character != '\u007f') {
            required = required + 1
            if (inIndentation && character == ' ') {
                indentation = indentation + 1
            } else {
                inIndentation = false
            }
        }
        if (required > state.gapEnd - state.gapStart) return state
        index = index + 1
    }
    var next = state
    index = 0
    while (index < text.length) {
        val character = text[index]
        if (character == '\n') {
            next = editInsertNewline(buffer, next)
        } else if (character == '\t') {
            next = editInsertTab(buffer, next)
        } else if (character >= ' ' && character != '\u007f') {
            next = editInsertCharacter(buffer, next, character)
        }
        index = index + 1
    }
    return next
}

private fun editRender(
    buffer: CharArray,
    state: EditState,
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
    repeat(16) { row ->
        val text = editVisibleLine(buffer, state, rowOffset + row, columnOffset, rowBuffer)
        if (text.isNotEmpty()) Terminal.writeAt(0, row + 1, text)
    }
    val cursorLine = editCursorLine(buffer, state)
    val cursorColumn = editCursorColumn(buffer, state)
    var message = status
    if (confirmExit) {
        message = "Save modified buffer? Y/N"
    } else if (message == "") {
        message = "Ln ${cursorLine + 1}, Col ${cursorColumn + 1}"
    }
    Terminal.writeAt(0, 17, message)
    Terminal.writeAt(0, 18, "^S Save  ^X Exit")
    Terminal.setCursor(cursorColumn - columnOffset, cursorLine - rowOffset + 1)
    Terminal.setCursorVisible(!confirmExit)
}

private fun editVisibleLine(
    buffer: CharArray,
    state: EditState,
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
    state: EditState,
    path: String,
): Int = FileSystem.writeText(path, editText(buffer, state))

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

private fun editStartsWith(
    value: String,
    lower: Char,
    upper: Char,
): Boolean = value.isNotEmpty() && (value[0] == lower || value[0] == upper)

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
