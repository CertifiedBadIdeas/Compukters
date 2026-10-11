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

package compukter.system.shell

import compukter.terminal.Terminal
import compukter.terminal.TerminalColor
import compukter.terminal.TerminalEvent
import compukter.terminal.TerminalKey
import compukter.terminal.TerminalKeyAction
import kotlin.math.Rect2i
import kotlin.math.Vector2i

/** Bounded session history; draft edits never change stored commands. */
class ShellEditor {
    private val history = Array(32) { "" }
    private var count = 0

    private fun remember(line: String) {
        if (line.isNotBlank() && (count == 0 || history[count - 1] != line)) {
            if (count == history.size) {
                for (index in 1 until count) history[index - 1] = history[index]
                count--
            }
            history[count] = line
            count++
        }
    }

    fun readCommand(): String? {
        Terminal.setColors(TerminalColor.WHITE, TerminalColor.BLACK)
        if (Terminal.cursor.x != 0) println()
        print("> ")
        val row = Terminal.cursor.y
        val width = Terminal.size.x
        var line = ""
        var cursor = 0
        var offset = 0
        var selected = count
        var draft = ""
        var draftCursor = 0
        while (true) {
            offset = shellRenderLine(line, cursor, offset, row, width)
            when (val event = Terminal.readEvent(if (line.isEmpty()) 256 else 0)) {
                is TerminalEvent.Text -> {
                    val inserted = shellInputText(event.text, 256 - line.length)
                    line = line.substring(0, cursor) + inserted + line.substring(cursor, line.length)
                    cursor += inserted.length
                }

                is TerminalEvent.SubmittedLine -> {
                    line = event.text
                    Terminal.fill(Rect2i(Vector2i(0, row), Vector2i(width, 1)))
                    Terminal.setCursor(Vector2i(0, row))
                    print("> " + line + "\n")
                    remember(line)
                    return line
                }

                is TerminalEvent.Key -> {
                    val key = event.key
                    if (event.modifiers.control && key == TerminalKey.C) {
                        Terminal.setCursor(Vector2i(0, row))
                        Terminal.fill(Rect2i(Vector2i(0, row), Vector2i(width, 1)))
                        print("> ^C\n")
                        return null
                    } else if (event.modifiers.control && key == TerminalKey.U) {
                        line = ""
                        cursor = 0
                    } else {
                        when (key) {
                            TerminalKey.ENTER -> {
                                if (event.action == TerminalKeyAction.PRESS) {
                                    Terminal.fill(Rect2i(Vector2i(0, row), Vector2i(width, 1)))
                                    Terminal.setCursor(Vector2i(0, row))
                                    print("> " + line + "\n")
                                    remember(line)
                                    return line
                                }
                            }

                            TerminalKey.LEFT -> {
                                cursor = shellPrevious(line, cursor)
                            }

                            TerminalKey.RIGHT -> {
                                cursor = shellNext(line, cursor)
                            }

                            TerminalKey.HOME -> {
                                cursor = 0
                            }

                            TerminalKey.END -> {
                                cursor = line.length
                            }

                            TerminalKey.BACKSPACE -> {
                                if (cursor > 0) {
                                    val previous = shellPrevious(line, cursor)
                                    line = line.substring(0, previous) + line.substring(cursor, line.length)
                                    cursor = previous
                                }
                            }

                            TerminalKey.DELETE -> {
                                if (cursor < line.length) {
                                    line = line.substring(0, cursor) + line.substring(shellNext(line, cursor), line.length)
                                }
                            }

                            TerminalKey.UP -> {
                                if (selected > 0) {
                                    if (selected == count) {
                                        draft = line
                                        draftCursor = cursor
                                    }
                                    selected--
                                    line = history[selected]
                                    cursor = line.length
                                    offset = 0
                                }
                            }

                            TerminalKey.DOWN -> {
                                if (selected < count) {
                                    selected++
                                    line = if (selected == count) draft else history[selected]
                                    cursor = if (selected == count) draftCursor else line.length
                                    offset = 0
                                }
                            }

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

private fun shellPrevious(
    text: String,
    index: Int,
): Int {
    if (index == 0) return 0
    val previous = index - 1
    return if (previous > 0 && text[previous].code in 56320..57343 && text[previous - 1].code in 55296..56319) previous - 1 else previous
}

private fun shellNext(
    text: String,
    index: Int,
): Int {
    if (index == text.length) return index
    return if (text[index].code in 55296..56319 && index + 1 < text.length && text[index + 1].code in 56320..57343) index + 2 else index + 1
}

private fun shellInputText(
    text: String,
    capacity: Int,
): String {
    val buffer = CharArray(capacity)
    var length = 0
    var index = 0
    while (index < text.length) {
        val end = shellNext(text, index)
        if (text[index] >= ' ' && text[index] != '\u007f') {
            if (length + end - index > capacity) break
            while (index < end) {
                buffer[length] = text[index]
                length++
                index++
            }
        } else {
            index = end
        }
    }
    return buffer.concatToString(0, length)
}

private fun shellColumns(
    text: String,
    start: Int,
    end: Int,
): Int {
    var index = start
    var columns = 0
    while (index < end) {
        index = shellNext(text, index)
        columns++
    }
    return columns
}

private fun shellRenderLine(
    text: String,
    cursor: Int,
    previousOffset: Int,
    row: Int,
    width: Int,
): Int {
    var offset = previousOffset
    if (offset > cursor) offset = cursor
    var cursorColumns = shellColumns(text, offset, cursor)
    while (cursorColumns >= width - 2) {
        offset = shellNext(text, offset)
        cursorColumns--
    }
    var end = offset
    var columns = 0
    while (end < text.length && columns < width - 2) {
        end = shellNext(text, end)
        columns++
    }
    Terminal.fill(Rect2i(Vector2i(0, row), Vector2i(width, 1)))
    Terminal.writeAt(Vector2i(0, row), "> " + text.substring(offset, end))
    Terminal.setCursor(Vector2i(2 + cursorColumns, row))
    Terminal.setCursorVisible(true)
    return offset
}
