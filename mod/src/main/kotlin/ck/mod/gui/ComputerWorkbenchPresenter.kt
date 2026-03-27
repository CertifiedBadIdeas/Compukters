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
package ck.mod.gui

import ck.lang.frontend.LanguageIde
import ck.lang.runtime.CompletionItem
import ck.lang.runtime.CompletionItemKind
import ck.lang.runtime.ComputerIdeSnapshot
import ck.lang.runtime.ComputerWorkspaceDocument
import ck.lang.runtime.ComputerWorkspaceEntry
import ck.lang.runtime.HighlightTokenKind
import ck.lang.runtime.HoverInfo
import ck.mod.gui.input.ClientInputHandler
import ck.mod.gui.input.InputHandler
import ck.mod.gui.terminal.Terminal
import ck.mod.language.LanguageServices
import ck.mod.menu.AbstractComputerMenu
import ck.mod.network.ClientNetworking
import ck.mod.network.server.ComputerWorkspaceServerMessage
import net.minecraft.client.gui.Font
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max

data class ComputerWorkbenchLayout(
    val leftPos: Int,
    val topPos: Int,
    val imageWidth: Int,
    val imageHeight: Int,
)

class ComputerWorkbenchPresenter<T : AbstractComputerMenu>(
    val menu: T,
) {
    enum class Mode {
        TERMINAL,
        EDITOR,
    }

    private val ide: LanguageIde = LanguageServices.ide

    val terminalData: Terminal = menu.getTerminal()
    val family = menu.family
    val input: InputHandler = ClientInputHandler(menu)

    var mode: Mode = Mode.TERMINAL
        private set

    var browserPath: String = ""
        private set

    var openDocument: ComputerWorkspaceDocument? = null
        private set

    var cachedEntries: List<ComputerWorkspaceEntry> = emptyList()
        private set

    var editorText: String = ""
        private set

    var editorDirty: Boolean = false
        private set

    var editorScrollLine: Int = 0
        private set

    var cursorLine: Int = 0
        private set

    var cursorColumn: Int = 0
        private set

    var ideSnapshot: ComputerIdeSnapshot? = null
        private set

    var hoverInfo: HoverInfo? = null
        private set

    var completionItems: List<CompletionItem> = emptyList()
        private set

    var selectedCompletion: Int = 0
        private set

    fun init() {
        requestListing("")
        syncWorkspaceState()
        refreshIde()
    }

    fun tick() {
        syncWorkspaceState()
    }

    fun toggleMode() {
        mode = if (mode == Mode.TERMINAL) Mode.EDITOR else Mode.TERMINAL
        if (mode == Mode.EDITOR) {
            requestListing(browserPath)
        }
    }

    fun syncWorkspaceState() {
        val latestEntries = menu.getWorkspaceEntries()
        if (latestEntries != cachedEntries) {
            cachedEntries = latestEntries
        }

        val latestDocument = menu.getWorkspaceDocument()
        if (latestDocument != null && latestDocument != openDocument) {
            openDocument = latestDocument
            editorText = latestDocument.text
            editorDirty = false
            cursorLine = 0
            cursorColumn = 0
            editorScrollLine = 0
            refreshIde()
        }
    }

    fun requestListing(path: String) {
        browserPath = path.trim('/').trim()
        ClientNetworking.sendToServer(ComputerWorkspaceServerMessage(menu, ComputerWorkspaceServerMessage.Action.LIST, browserPath))
    }

    fun requestDocument(path: String) {
        ClientNetworking.sendToServer(ComputerWorkspaceServerMessage(menu, ComputerWorkspaceServerMessage.Action.READ, path))
    }

    fun saveDocument() {
        val path = openDocument?.path ?: return
        ClientNetworking.sendToServer(ComputerWorkspaceServerMessage(menu, ComputerWorkspaceServerMessage.Action.WRITE, path, editorText))
        editorDirty = false
        refreshIde()
    }

    fun navigateUp() {
        browserPath = browserPath.substringBeforeLast('/', "")
        requestListing(browserPath)
    }

    fun refreshIde() {
        val document = openDocument ?: return
        ideSnapshot =
            ide.analyze(document.path, editorText).let { snapshot ->
                ComputerIdeSnapshot(document.copy(text = editorText), snapshot.diagnostics, snapshot.highlights)
            }
        hoverInfo = null
        if (completionItems.isNotEmpty()) {
            completionItems = emptyList()
        }
    }

    fun updateHover(
        line: Int,
        column: Int,
    ) {
        val document = openDocument ?: return
        hoverInfo = ide.hover(document.path, editorText, line, column)
    }

    fun clearHover() {
        hoverInfo = null
    }

    fun openCompletion() {
        val document = openDocument ?: return
        completionItems =
            ide.complete(
                document.path,
                editorText,
                cursorLine,
                cursorColumn,
            )
        selectedCompletion = 0
    }

    fun applyCompletion() {
        val item = completionItems.getOrNull(selectedCompletion) ?: return
        val lines = editorLines().toMutableList()
        val line = lines[cursorLine]
        val prefixStart = findIdentifierStart(line, cursorColumn)
        lines[cursorLine] = line.substring(0, prefixStart) + item.label + line.substring(cursorColumn)
        cursorColumn = prefixStart + item.label.length
        editorText = lines.joinToString("\n")
        editorDirty = true
        completionItems = emptyList()
        refreshIde()
    }

    fun navigateToDefinition() {
        val document = openDocument ?: return
        val target =
            ide
                .definition(
                    document.path,
                    editorText,
                    cursorLine,
                    cursorColumn,
                )?.takeIf { it.path == document.path } ?: return
        cursorLine = target.range.start.line
        cursorColumn = target.range.start.column
        keepCursorVisible(DEFAULT_VISIBLE_EDITOR_LINES)
    }

    fun keyPressed(
        key: Int,
        scancode: Int,
        modifiers: Int,
        visibleEditorLines: Int = DEFAULT_VISIBLE_EDITOR_LINES,
    ): Boolean {
        if (key == GLFW.GLFW_KEY_F4) {
            toggleMode()
            return true
        }
        if (mode != Mode.EDITOR) {
            return false
        }

        if ((modifiers and GLFW.GLFW_MOD_CONTROL) != 0) {
            when (key) {
                GLFW.GLFW_KEY_S -> {
                    saveDocument()
                    return true
                }

                GLFW.GLFW_KEY_SPACE -> {
                    openCompletion()
                    return true
                }
            }
        }

        if (completionItems.isNotEmpty()) {
            when (key) {
                GLFW.GLFW_KEY_UP -> {
                    selectedCompletion = (selectedCompletion - 1).floorMod(completionItems.size)
                    return true
                }

                GLFW.GLFW_KEY_DOWN -> {
                    selectedCompletion = (selectedCompletion + 1).floorMod(completionItems.size)
                    return true
                }

                GLFW.GLFW_KEY_ENTER,
                GLFW.GLFW_KEY_KP_ENTER,
                -> {
                    applyCompletion()
                    return true
                }

                GLFW.GLFW_KEY_ESCAPE -> {
                    completionItems = emptyList()
                    return true
                }
            }
        }

        when (key) {
            GLFW.GLFW_KEY_LEFT -> moveCursorHorizontal(-1, visibleEditorLines)

            GLFW.GLFW_KEY_RIGHT -> moveCursorHorizontal(1, visibleEditorLines)

            GLFW.GLFW_KEY_UP -> moveCursorVertical(-1, visibleEditorLines)

            GLFW.GLFW_KEY_DOWN -> moveCursorVertical(1, visibleEditorLines)

            GLFW.GLFW_KEY_BACKSPACE -> deleteBackward()

            GLFW.GLFW_KEY_DELETE -> deleteForward()

            GLFW.GLFW_KEY_ENTER,
            GLFW.GLFW_KEY_KP_ENTER,
            -> insertText("\n", visibleEditorLines)

            GLFW.GLFW_KEY_TAB -> insertText("    ", visibleEditorLines)

            GLFW.GLFW_KEY_PAGE_UP -> editorScrollLine = (editorScrollLine - visibleEditorLines).coerceAtLeast(0)

            GLFW.GLFW_KEY_PAGE_DOWN -> editorScrollLine += visibleEditorLines

            GLFW.GLFW_KEY_F12 -> navigateToDefinition()

            else -> return true
        }

        refreshIde()
        return true
    }

    fun charTyped(
        ch: Char,
        visibleEditorLines: Int = DEFAULT_VISIBLE_EDITOR_LINES,
    ): Boolean {
        if (mode != Mode.EDITOR) {
            return false
        }
        if (!Character.isISOControl(ch)) {
            insertText(ch.toString(), visibleEditorLines)
            refreshIde()
        }
        return true
    }

    fun handleFileListClick(
        layout: ComputerWorkbenchLayout,
        mouseX: Int,
        mouseY: Int,
    ): Boolean {
        if (mouseX !in (layout.leftPos + 10)..(layout.leftPos + 124)) return false

        var rowY = layout.topPos + 54
        if (browserPath.isNotEmpty() && mouseY in rowY..(rowY + 10)) {
            navigateUp()
            return true
        }
        if (browserPath.isNotEmpty()) {
            rowY += 12
        }

        cachedEntries.forEach { entry ->
            if (mouseY in rowY..(rowY + 10)) {
                if (entry.directory) {
                    requestListing(entry.path)
                } else {
                    requestDocument(entry.path)
                }
                return true
            }
            rowY += 12
        }
        return false
    }

    fun handleCompletionClick(
        layout: ComputerWorkbenchLayout,
        font: Font,
        mouseX: Int,
        mouseY: Int,
    ): Boolean {
        if (completionItems.isEmpty()) return false
        val lines = editorLines()
        val visibleLine = cursorLine - editorScrollLine
        val beforeCursor = lines.getOrElse(cursorLine) { "" }.take(cursorColumn)
        val popupX = layout.leftPos + 176 + font.width(beforeCursor)
        val popupY = layout.topPos + 52 + visibleLine * LINE_HEIGHT
        if (mouseX < popupX || mouseX > popupX + 220 || mouseY < popupY || mouseY > popupY + 100) return false
        val index = ((mouseY - popupY - 2) / 12).coerceIn(0, completionItems.size - 1)
        selectedCompletion = index
        applyCompletion()
        return true
    }

    fun isInEditorArea(
        layout: ComputerWorkbenchLayout,
        mouseX: Int,
        mouseY: Int,
    ): Boolean =
        mouseX in (layout.leftPos + 136)..(layout.leftPos + layout.imageWidth - 10) &&
            mouseY in (layout.topPos + 34)..(layout.topPos + layout.imageHeight - 32)

    fun placeCursorAt(
        layout: ComputerWorkbenchLayout,
        font: Font,
        mouseX: Int,
        mouseY: Int,
        visibleEditorLines: Int = DEFAULT_VISIBLE_EDITOR_LINES,
    ) {
        val target = mouseToCursor(layout, font, mouseX, mouseY)
        cursorLine = target.first
        cursorColumn = target.second
        keepCursorVisible(visibleEditorLines)
    }

    fun mouseToCursor(
        layout: ComputerWorkbenchLayout,
        font: Font,
        mouseX: Int,
        mouseY: Int,
    ): Pair<Int, Int> {
        val lines = editorLines()
        val lineIndex = ((mouseY - (layout.topPos + 40)) / LINE_HEIGHT + editorScrollLine).coerceIn(0, max(lines.lastIndex, 0))
        val line = lines.getOrElse(lineIndex) { "" }
        val relativeX = mouseX - (layout.leftPos + 176)
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

        return lineIndex to bestColumn
    }

    fun editorLines(): List<String> = if (editorText.isEmpty()) listOf("") else editorText.split('\n')

    fun visibleEditorLines(layout: ComputerWorkbenchLayout): Int = ((layout.imageHeight - 82) / LINE_HEIGHT).coerceAtLeast(1)

    fun scrollEditor(deltaLines: Int) {
        editorScrollLine = (editorScrollLine + deltaLines).coerceAtLeast(0)
    }

    fun completionDetail(kind: CompletionItemKind): String =
        when (kind) {
            CompletionItemKind.KEYWORD -> "kw"
            CompletionItemKind.MODULE -> "mod"
            CompletionItemKind.FUNCTION -> "fn"
            CompletionItemKind.VARIABLE -> "var"
            CompletionItemKind.PARAMETER -> "arg"
            CompletionItemKind.TYPE -> "type"
            CompletionItemKind.FIELD -> "field"
        }

    fun highlightColor(kind: HighlightTokenKind): Int =
        when (kind) {
            HighlightTokenKind.KEYWORD -> 0x8EC5FF
            HighlightTokenKind.STRING -> 0xD9C27C
            HighlightTokenKind.NUMBER -> 0xC6A0F6
            HighlightTokenKind.BOOLEAN -> 0xC6A0F6
            HighlightTokenKind.NULL -> 0xC6A0F6
            HighlightTokenKind.IDENTIFIER -> 0xE6ECF5
            HighlightTokenKind.FUNCTION -> 0x8BD5CA
            HighlightTokenKind.TYPE -> 0xF5B971
            HighlightTokenKind.MODULE -> 0x7FC1FF
            HighlightTokenKind.FIELD -> 0xA8D68F
            HighlightTokenKind.OPERATOR -> 0xE6ECF5
            HighlightTokenKind.PUNCTUATION -> 0xB0B8C5
        }

    private fun moveCursorHorizontal(
        delta: Int,
        visibleEditorLines: Int,
    ) {
        val lines = editorLines()
        if (delta < 0 && cursorColumn == 0 && cursorLine > 0) {
            cursorLine -= 1
            cursorColumn = lines[cursorLine].length
        } else if (delta > 0 && cursorColumn >= lines[cursorLine].length && cursorLine < lines.lastIndex) {
            cursorLine += 1
            cursorColumn = 0
        } else {
            cursorColumn = (cursorColumn + delta).coerceIn(0, lines[cursorLine].length)
        }
        keepCursorVisible(visibleEditorLines)
    }

    private fun moveCursorVertical(
        delta: Int,
        visibleEditorLines: Int,
    ) {
        val lines = editorLines()
        cursorLine = (cursorLine + delta).coerceIn(0, lines.lastIndex)
        cursorColumn = cursorColumn.coerceIn(0, lines[cursorLine].length)
        keepCursorVisible(visibleEditorLines)
    }

    private fun deleteBackward() {
        if (cursorLine == 0 && cursorColumn == 0) return
        val lines = editorLines().toMutableList()
        if (cursorColumn > 0) {
            val line = lines[cursorLine]
            lines[cursorLine] = line.removeRange(cursorColumn - 1, cursorColumn)
            cursorColumn -= 1
        } else {
            val prev = lines[cursorLine - 1]
            val current = lines.removeAt(cursorLine)
            cursorLine -= 1
            cursorColumn = prev.length
            lines[cursorLine] = prev + current
        }
        editorText = lines.joinToString("\n")
        editorDirty = true
    }

    private fun deleteForward() {
        val lines = editorLines().toMutableList()
        val line = lines[cursorLine]
        if (cursorColumn < line.length) {
            lines[cursorLine] = line.removeRange(cursorColumn, cursorColumn + 1)
        } else if (cursorLine < lines.lastIndex) {
            lines[cursorLine] = line + lines.removeAt(cursorLine + 1)
        } else {
            return
        }
        editorText = lines.joinToString("\n")
        editorDirty = true
    }

    private fun insertText(
        text: String,
        visibleEditorLines: Int,
    ) {
        val lines = editorLines().toMutableList()
        val line = lines[cursorLine]
        val before = line.substring(0, cursorColumn)
        val after = line.substring(cursorColumn)
        if (text == "\n") {
            lines[cursorLine] = before
            lines.add(cursorLine + 1, after)
            cursorLine += 1
            cursorColumn = 0
        } else {
            lines[cursorLine] = before + text + after
            cursorColumn += text.length
        }
        editorText = lines.joinToString("\n")
        editorDirty = true
        keepCursorVisible(visibleEditorLines)
    }

    private fun keepCursorVisible(visibleEditorLines: Int) {
        if (cursorLine < editorScrollLine) {
            editorScrollLine = cursorLine
        }
        val maxVisible = editorScrollLine + visibleEditorLines - 1
        if (cursorLine > maxVisible) {
            editorScrollLine = cursorLine - visibleEditorLines + 1
        }
        editorScrollLine = editorScrollLine.coerceAtLeast(0)
    }

    private fun findIdentifierStart(
        line: String,
        start: Int,
    ): Int {
        var index = start
        while (index > 0) {
            val ch = line[index - 1]
            if (!(ch.isLetterOrDigit() || ch == '_')) {
                break
            }
            index -= 1
        }
        return index
    }

    private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

    companion object {
        const val LINE_HEIGHT: Int = 10
        const val DEFAULT_VISIBLE_EDITOR_LINES: Int = 20
    }
}
