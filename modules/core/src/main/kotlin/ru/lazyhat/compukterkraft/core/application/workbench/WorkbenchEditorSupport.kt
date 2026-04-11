/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.core.application.workbench

import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import kotlin.collections.emptyList
import kotlin.math.abs
import kotlin.math.max

internal fun EditorState.lines(): List<String> = if (text.isEmpty()) listOf("") else text.split('\n')

internal fun EditorState.keepCursorVisible(visibleEditorLines: Int): EditorState {
    var nextScrollLine = scrollLine
    if (cursorLine < nextScrollLine) {
        nextScrollLine = cursorLine
    }
    val maxVisible = nextScrollLine + visibleEditorLines - 1
    if (cursorLine > maxVisible) {
        nextScrollLine = cursorLine - visibleEditorLines + 1
    }
    return copy(scrollLine = nextScrollLine.coerceAtLeast(0))
}

internal fun EditorState.withCursor(
    line: Int,
    column: Int,
    visibleEditorLines: Int,
): EditorState = copy(cursorLine = line, cursorColumn = column).keepCursorVisible(visibleEditorLines)

internal fun EditorState.moveCursorHorizontal(
    delta: Int,
    visibleEditorLines: Int,
): EditorState {
    val lines = lines()
    val nextState =
        when {
            delta < 0 && cursorColumn == 0 && cursorLine > 0 -> {
                copy(
                    cursorLine = cursorLine - 1,
                    cursorColumn = lines[cursorLine - 1].length,
                )
            }

            delta > 0 && cursorColumn >= lines[cursorLine].length && cursorLine < lines.lastIndex -> {
                copy(
                    cursorLine = cursorLine + 1,
                    cursorColumn = 0,
                )
            }

            else -> {
                copy(cursorColumn = (cursorColumn + delta).coerceIn(0, lines[cursorLine].length))
            }
        }

    return nextState.keepCursorVisible(visibleEditorLines)
}

internal fun EditorState.moveCursorVertical(
    delta: Int,
    visibleEditorLines: Int,
): EditorState {
    val lines = lines()
    val nextLine = (cursorLine + delta).coerceIn(0, lines.lastIndex)
    val nextColumn = cursorColumn.coerceIn(0, lines[nextLine].length)
    return copy(cursorLine = nextLine, cursorColumn = nextColumn).keepCursorVisible(visibleEditorLines)
}

internal fun EditorState.scrollBy(deltaLines: Int): EditorState = copy(scrollLine = (scrollLine + deltaLines).coerceAtLeast(0))

internal fun EditorState.insertText(
    insertedText: String,
    visibleEditorLines: Int,
): EditorState {
    val lines = lines().toMutableList()
    val line = lines[cursorLine]
    val before = line.substring(0, cursorColumn)
    val after = line.substring(cursorColumn)
    val nextState =
        if (insertedText == "\n") {
            lines[cursorLine] = before
            lines.add(cursorLine + 1, after)
            copy(
                text = lines.joinToString("\n"),
                dirty = true,
                cursorLine = cursorLine + 1,
                cursorColumn = 0,
            )
        } else {
            lines[cursorLine] = before + insertedText + after
            copy(
                text = lines.joinToString("\n"),
                dirty = true,
                cursorColumn = cursorColumn + insertedText.length,
            )
        }

    return nextState.keepCursorVisible(visibleEditorLines)
}

internal fun EditorState.deleteBackward(): EditorState {
    if (cursorLine == 0 && cursorColumn == 0) return this

    val lines = lines().toMutableList()
    var nextCursorLine = cursorLine
    var nextCursorColumn = cursorColumn

    if (cursorColumn > 0) {
        val line = lines[cursorLine]
        lines[cursorLine] = line.removeRange(cursorColumn - 1, cursorColumn)
        nextCursorColumn -= 1
    } else {
        val previous = lines[cursorLine - 1]
        val current = lines.removeAt(cursorLine)
        nextCursorLine -= 1
        nextCursorColumn = previous.length
        lines[nextCursorLine] = previous + current
    }

    return copy(
        text = lines.joinToString("\n"),
        dirty = true,
        cursorLine = nextCursorLine,
        cursorColumn = nextCursorColumn,
    )
}

internal fun EditorState.deleteForward(): EditorState {
    val lines = lines().toMutableList()
    val line = lines[cursorLine]
    when {
        cursorColumn < line.length -> {
            lines[cursorLine] = line.removeRange(cursorColumn, cursorColumn + 1)
        }

        cursorLine < lines.lastIndex -> {
            lines[cursorLine] = line + lines.removeAt(cursorLine + 1)
        }

        else -> {
            return this
        }
    }

    return copy(
        text = lines.joinToString("\n"),
        dirty = true,
    )
}

internal fun EditorState.applyCompletion(item: CompletionItem): EditorState {
    val lines = lines().toMutableList()
    val line = lines[cursorLine]
    val prefixStart = line.findIdentifierStart(cursorColumn)
    val textToInsert = item.insertText ?: item.label
    lines[cursorLine] = line.substring(0, prefixStart) + textToInsert + line.substring(cursorColumn)
    return copy(
        text = lines.joinToString("\n"),
        dirty = true,
        cursorColumn = prefixStart + textToInsert.length,
        completionItems = emptyList(),
        selectedCompletion = 0,
    )
}

internal fun EditorState.moveCursorToMouse(
    font: FontMetrics,
    editorOriginX: Int,
    editorOriginY: Int,
    mouseX: Int,
    mouseY: Int,
    lineHeight: Int,
    visibleEditorLines: Int,
): EditorState {
    val lines = lines()
    val lineIndex = ((mouseY - editorOriginY) / lineHeight + scrollLine).coerceIn(0, max(lines.lastIndex, 0))
    val line = lines.getOrElse(lineIndex) { "" }
    val relativeX = mouseX - editorOriginX
    var bestColumn = 0
    var bestDistance = Int.MAX_VALUE

    for (index in 0..line.length) {
        val width = font.width(line.take(index))
        val distance = abs(relativeX - width)
        if (distance < bestDistance) {
            bestDistance = distance
            bestColumn = index
        }
    }

    return withCursor(lineIndex, bestColumn, visibleEditorLines)
}

private fun String.findIdentifierStart(start: Int): Int {
    var index = start
    while (index > 0) {
        val ch = this[index - 1]
        if (!(ch.isLetterOrDigit() || ch == '_')) {
            break
        }
        index -= 1
    }
    return index
}
