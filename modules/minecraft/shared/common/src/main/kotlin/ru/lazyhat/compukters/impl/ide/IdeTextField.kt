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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorLimits
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.math.roundToInt

data class IdeTextFieldView(
    val text: String,
    val caretUtf16: Int = text.length,
    val selection: EditorRange? = null,
    val firstColumn: Int = 0,
    val masked: Boolean = false,
    val revealCaret: Boolean = true,
) {
    override fun toString() =
        "IdeTextFieldView(text=${if (masked) "•".repeat(text.codePointCount(0, text.length)) else text}, caret=$caretUtf16)"
}

data class IdeTextFieldLayout(
    val bounds: IdeRect,
    val inside: IdeRect,
    val text: String,
    val firstColumn: Int,
    val columns: Int,
    val textX: Int,
    val rowTop: Int,
    val caret: IdeRect?,
    val selection: IdeRect?,
) {
    fun offsetAt(
        x: Double,
        value: String,
        font: IdeCodeFontProfile,
    ): Int {
        val point = (firstColumn + ((x - textX) / font.cellWidth).roundToInt()).coerceIn(0, value.codePointCount(0, value.length))
        return value.offsetByCodePoints(0, point)
    }

    companion object {
        fun compute(
            view: IdeTextFieldView,
            bounds: IdeRect,
            font: IdeCodeFontProfile = IdeCodeFontProfile.DEFAULT,
        ): IdeTextFieldLayout {
            val inside = IdeRect(bounds.left + 1, bounds.top + 1, bounds.right - 1, bounds.bottom - 1)
            val columns = ((inside.width - 8) / font.cellWidth).coerceAtLeast(1)
            val count = view.text.codePointCount(0, view.text.length)
            val caretColumn = view.text.codePointCount(0, view.caretUtf16.coerceIn(0, view.text.length))
            var first = view.firstColumn.coerceIn(0, (count - columns + 1).coerceAtLeast(0))
            if (view.revealCaret) {
                if (caretColumn < first) first = caretColumn
                if (caretColumn >= first + columns) first = caretColumn - columns + 1
            }
            val start = view.text.offsetByCodePoints(0, first)
            val end = view.text.offsetByCodePoints(0, (first + columns).coerceAtMost(count))
            val visible =
                if (view.masked) {
                    "•".repeat(
                        (first + columns).coerceAtMost(count) - first,
                    )
                } else {
                    view.text.substring(start, end)
                }
            val x = inside.left + 4
            val top = font.centeredCellTop(inside)
            val caretX = x + (caretColumn - first) * font.cellWidth
            val caret = if (caretX >= x && caretX < inside.right - 3) IdeRect(caretX, top, caretX + 1, top + font.cellHeight) else null
            val selection =
                view.selection?.let {
                    val left = view.text.codePointCount(0, it.startUtf16).coerceAtLeast(first)
                    val right = view.text.codePointCount(0, it.endUtf16).coerceAtMost(first + columns)
                    if (right >
                        left
                    ) {
                        IdeRect(x + (left - first) * font.cellWidth, top, x + (right - first) * font.cellWidth, top + font.cellHeight)
                    } else {
                        null
                    }
                }
            return IdeTextFieldLayout(bounds, inside, visible, first, columns, x, top, caret, selection)
        }
    }
}

/** Single-line editing and viewport state shared by all modal fields. */
class IdeTextField(
    initial: String,
    private val maximum: Int,
    private val masked: Boolean = false,
) : AutoCloseable {
    private val document =
        EditorDocument(
            normalize(initial, maximum),
            EditorLimits(
                maxCodeUnits = maximum,
                maxUtf8Bytes = maximum * 4,
                maxUndoEntries = 128,
                maxUndoCodeUnits =
                    maximum * 32,
            ),
        )
    private var firstColumn = 0
    private var revealCaret = true
    private var captured = false

    init {
        document.setCaret(document.length)
    }

    fun view() = IdeTextFieldView(document.materialize(), document.caretOffset, document.selectionRange, firstColumn, masked, revealCaret)

    fun layout(bounds: IdeRect): IdeTextFieldLayout = IdeTextFieldLayout.compute(view(), bounds).also { firstColumn = it.firstColumn }

    fun type(
        value: String,
        paste: Boolean = false,
    ) {
        val available = maximum - document.length + (document.selectionRange?.length ?: 0)
        val text = normalize(value, available)
        if (text.isNotEmpty()) {
            if (paste) document.paste(text) else document.type(text)
        }
        revealCaret = true
    }

    fun keyPressed(
        event: IdeKeyInput,
        readClipboard: () -> String,
        writeClipboard: (String) -> Unit,
    ): Boolean {
        val control = event.modifiers and IdeModifier.CONTROL != 0
        val shift = event.modifiers and IdeModifier.SHIFT != 0
        when {
            event.paste || (control && event.key == IdeKeyCode.V) -> {
                type(readClipboard(), paste = true)
            }

            control && event.key == IdeKeyCode.A -> {
                document.selectAll()
            }

            control && event.key == IdeKeyCode.C -> {
                if (!masked) selectedText()?.let(writeClipboard)
            }

            control && event.key == IdeKeyCode.X -> {
                if (!masked) {
                    selectedText()?.let {
                        writeClipboard(it)
                        document.backspace()
                    }
                }
            }

            control && event.key == IdeKeyCode.Z -> {
                if (shift) document.redo() else document.undo()
            }

            control && event.key == IdeKeyCode.Y -> {
                document.redo()
            }

            event.key == IdeKeyCode.LEFT -> {
                if (control) document.moveWordLeft(shift) else document.moveLeft(shift)
            }

            event.key == IdeKeyCode.RIGHT -> {
                if (control) document.moveWordRight(shift) else document.moveRight(shift)
            }

            event.key == IdeKeyCode.HOME -> {
                document.moveHome(shift)
            }

            event.key == IdeKeyCode.END -> {
                document.moveEnd(shift)
            }

            event.key == IdeKeyCode.BACKSPACE -> {
                if (control) document.deleteWordBackward() else document.backspace()
            }

            event.key == IdeKeyCode.DELETE -> {
                if (control) document.deleteWordForward() else document.delete()
            }

            else -> {
                return false
            }
        }
        revealCaret = true
        return true
    }

    private fun selectedText() = document.selectionRange?.let(document::copyRange)

    fun press(
        x: Double,
        y: Double,
        bounds: IdeRect,
        shift: Boolean,
        doubleClick: Boolean,
    ): Boolean {
        if (!bounds.containsPoint(x, y)) return false
        val layout = layout(bounds)
        val offset = layout.offsetAt(x, document.materialize(), IdeCodeFontProfile.DEFAULT)
        document.setCaret(offset, shift)
        if (doubleClick && !masked && document.length > 0) {
            val tokenOffset = if (offset == document.length) document.materialize().offsetByCodePoints(offset, -1) else offset
            document.selectToken(tokenOffset)
        }
        captured = true
        revealCaret = true
        return true
    }

    fun drag(
        x: Double,
        bounds: IdeRect,
    ): Boolean {
        if (!captured) return false
        val layout = layout(bounds)
        if (x < layout.textX) firstColumn = (firstColumn - 1).coerceAtLeast(0)
        if (x >= layout.inside.right - 4) firstColumn++
        revealCaret = false
        val shifted = layout(bounds)
        document.setCaret(
            shifted.offsetAt(
                x.coerceIn(shifted.textX.toDouble(), (shifted.inside.right - 4).toDouble()),
                document.materialize(),
                IdeCodeFontProfile.DEFAULT,
            ),
            true,
        )
        revealCaret = true
        return true
    }

    fun release() {
        captured = false
        document.breakUndoGroup()
    }

    fun scroll(columns: Int) {
        firstColumn = (firstColumn + columns).coerceAtLeast(0)
        revealCaret = false
    }

    override fun close() {
        captured = false
        document.close()
    }

    companion object {
        private fun normalize(
            value: String,
            maximum: Int,
        ): String {
            val result = StringBuilder()
            var offset = 0
            while (offset < value.length) {
                val point = value.codePointAt(offset)
                offset += Character.charCount(point)
                if (point in 0xD800..0xDFFF) continue
                val admitted = if (Character.isISOControl(point)) 32 else point
                if (result.length + Character.charCount(admitted) > maximum) break
                result.appendCodePoint(admitted)
            }
            return result.toString()
        }
    }
}

data class IdeTextFieldDraw(
    val fills: List<IdeFillDraw>,
    val text: IdeTextDraw,
)

object IdeTextFields {
    fun draw(
        view: IdeTextFieldView,
        bounds: IdeRect,
        z: Int,
        font: IdeCodeFontProfile = IdeCodeFontProfile.DEFAULT,
    ): IdeTextFieldDraw {
        val layout = IdeTextFieldLayout.compute(view, bounds, font)
        val fills =
            mutableListOf(
                IdeFillDraw(IdeFillKind.Border, bounds, IdeColors.ACCENT, z),
                IdeFillDraw(IdeFillKind.Background, layout.inside, IdeColors.EDITOR, z + 1),
            )
        layout.selection?.let { fills += IdeFillDraw(IdeFillKind.Selection, it, IdeColors.SELECTION, z + 2) }
        layout.caret?.let { fills += IdeFillDraw(IdeFillKind.Caret, it, IdeColors.CARET, z + 3) }
        return IdeTextFieldDraw(
            fills,
            IdeTextDraw(
                IdeTextKind.Dialog,
                layout.text,
                layout.textX,
                layout.rowTop + font.glyphDrawOffsetY,
                IdeColors.TEXT,
                IdeTextStyle.Ui,
                font,
                layout.inside,
                null,
                z + 3,
            ),
        )
    }
}

/** Dialog and input use this same field rectangle. */
data class IdePromptGeometry(
    val bounds: IdeRect,
    val field: IdeRect,
) {
    companion object {
        fun compute(panel: IdeRect): IdePromptGeometry {
            val width = minOf(420, panel.width - 24)
            val height = minOf(132, panel.height - 24)
            val left = panel.left + (panel.width - width) / 2
            val top = panel.top + (panel.height - height) / 2
            val bounds = IdeRect(left, top, left + width, top + height)
            return IdePromptGeometry(bounds, IdeRect(bounds.left + 10, bounds.top + 44, bounds.right - 10, bounds.top + 66))
        }
    }
}
