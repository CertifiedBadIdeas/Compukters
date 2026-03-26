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
import ck.lang.runtime.ComputerDefinitionRequest
import ck.lang.runtime.ComputerHoverRequest
import ck.lang.runtime.ComputerIdeSnapshot
import ck.lang.runtime.ComputerWorkspaceDocument
import ck.lang.runtime.ComputerWorkspaceEntry
import ck.lang.runtime.HoverInfo
import ck.mod.gui.ComputerBorderRenderer.BORDER
import ck.mod.language.LanguageServices
import ck.mod.menu.AbstractComputerMenu
import ck.mod.network.ClientNetworking
import ck.mod.network.server.ComputerWorkspaceServerMessage
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

class ComputerWorkbenchScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : AbstractComputerScreen<T>(container, player, title, BORDER) {
    private enum class Mode {
        TERMINAL,
        EDITOR,
    }

    private val ide: LanguageIde = LanguageServices.ide
    private var mode = Mode.TERMINAL
    private var browserPath = ""
    private var openDocument: ComputerWorkspaceDocument? = null
    private var cachedEntries: List<ComputerWorkspaceEntry> = emptyList()
    private var editorText = ""
    private var editorDirty = false
    private var editorScrollLine = 0
    private var cursorLine = 0
    private var cursorColumn = 0
    private var ideSnapshot: ComputerIdeSnapshot? = null
    private var hoverInfo: HoverInfo? = null
    private var completionItems: List<CompletionItem> = emptyList()
    private var selectedCompletion = 0
    private lateinit var modeButton: Button
    private lateinit var saveButton: Button
    private lateinit var refreshButton: Button
    private lateinit var upButton: Button
    private lateinit var rebootButton: Button

    init {
        imageWidth = max(TerminalWidget.getWidth(terminalData.width) + BORDER * 2 + AbstractComputerMenu.SIDEBAR_WIDTH, 480)
        imageHeight = max(TerminalWidget.getHeight(terminalData.height) + BORDER * 2, 280)
    }

    override fun createTerminal(): TerminalWidget =
        TerminalWidget(terminalData, input, leftPos + AbstractComputerMenu.SIDEBAR_WIDTH + BORDER, topPos + BORDER)

    override fun init() {
        super.init()
        modeButton =
            addRenderableWidget(
                Button
                    .builder(Component.literal("IDE")) {
                        toggleMode()
                    }.bounds(leftPos + 8, topPos + 6, 48, 20)
                    .build(),
            )
        saveButton =
            addRenderableWidget(
                Button
                    .builder(Component.literal("Save")) {
                        saveDocument()
                    }.bounds(leftPos + 64, topPos + 6, 48, 20)
                    .build(),
            )
        refreshButton =
            addRenderableWidget(
                Button
                    .builder(Component.literal("Refresh")) {
                        requestListing(browserPath)
                        openDocument?.path?.let(::requestDocument)
                    }.bounds(leftPos + 120, topPos + 6, 62, 20)
                    .build(),
            )
        upButton =
            addRenderableWidget(
                Button
                    .builder(Component.literal("Up")) {
                        navigateUp()
                    }.bounds(leftPos + 190, topPos + 6, 40, 20)
                    .build(),
            )
        rebootButton =
            addRenderableWidget(
                Button
                    .builder(Component.literal("Reboot")) {
                        input.reboot()
                    }.bounds(leftPos + 238, topPos + 6, 62, 20)
                    .build(),
            )
        requestListing("")
        syncWorkspaceState()
        refreshIde()
        updateControls()
    }

    override fun containerTick() {
        super.containerTick()
        syncWorkspaceState()
        updateControls()
    }

    override fun keyPressed(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        if (key == GLFW.GLFW_KEY_F4) {
            toggleMode()
            return true
        }
        if (mode == Mode.EDITOR) {
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
                GLFW.GLFW_KEY_LEFT -> moveCursorHorizontal(-1)

                GLFW.GLFW_KEY_RIGHT -> moveCursorHorizontal(1)

                GLFW.GLFW_KEY_UP -> moveCursorVertical(-1)

                GLFW.GLFW_KEY_DOWN -> moveCursorVertical(1)

                GLFW.GLFW_KEY_BACKSPACE -> deleteBackward()

                GLFW.GLFW_KEY_DELETE -> deleteForward()

                GLFW.GLFW_KEY_ENTER,
                GLFW.GLFW_KEY_KP_ENTER,
                -> insertText("\n")

                GLFW.GLFW_KEY_TAB -> insertText("    ")

                GLFW.GLFW_KEY_PAGE_UP -> editorScrollLine = (editorScrollLine - visibleEditorLines()).coerceAtLeast(0)

                GLFW.GLFW_KEY_PAGE_DOWN -> editorScrollLine += visibleEditorLines()

                GLFW.GLFW_KEY_F12 -> navigateToDefinition()

                else -> return true
            }
            refreshIde()
            return true
        }
        return super.keyPressed(key, scancode, modifiers)
    }

    override fun charTyped(
        ch: Char,
        modifiers: Int,
    ): Boolean {
        if (mode == Mode.EDITOR) {
            if (!Character.isISOControl(ch)) {
                insertText(ch.toString())
                refreshIde()
            }
            return true
        }
        return super.charTyped(ch, modifiers)
    }

    override fun mouseClicked(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        if (mode == Mode.EDITOR) {
            if (super.mouseClicked(x, y, button)) return true
            if (button != 0) return false
            if (handleFileListClick(x.toInt(), y.toInt())) return true
            if (handleCompletionClick(x.toInt(), y.toInt())) return true
            if (isInEditorArea(x.toInt(), y.toInt())) {
                placeCursorAt(x.toInt(), y.toInt())
                return true
            }
            return false
        }
        return super.mouseClicked(x, y, button)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        delta: Double,
    ): Boolean {
        if (mode == Mode.EDITOR && isInEditorArea(mouseX.toInt(), mouseY.toInt())) {
            editorScrollLine = (editorScrollLine - delta.toInt()).coerceAtLeast(0)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, delta)
    }

    public override fun renderBg(
        graphics: GuiGraphics,
        partialTicks: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (mode == Mode.TERMINAL) {
            val terminal = getTerminal()
            val spriteRenderer =
                SpriteRenderer.createForGui(
                    graphics,
                    RenderTypes.GUI_SPRITES,
                )
            val computerTextures =
                GuiSprites.getComputerTextures(
                    family,
                )

            ComputerBorderRenderer.render(
                spriteRenderer,
                computerTextures,
                terminal.x,
                terminal.y,
                terminal.getWidth(),
                terminal.getHeight(),
                false,
            )
            ComputerSidebar.renderBackground(spriteRenderer, computerTextures, leftPos, topPos + sidebarYOffset)
            graphics.flush()
        } else {
            graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF12151D.toInt())
            graphics.fill(leftPos + 8, topPos + 34, leftPos + 128, topPos + imageHeight - 12, 0xFF1D2330.toInt())
            graphics.fill(leftPos + 136, topPos + 34, leftPos + imageWidth - 8, topPos + imageHeight - 32, 0xFF0D1016.toInt())
            graphics.fill(leftPos + 136, topPos + imageHeight - 28, leftPos + imageWidth - 8, topPos + imageHeight - 8, 0xFF161B25.toInt())
        }
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        renderBackground(graphics)
        super.render(graphics, mouseX, mouseY, partialTicks)
        if (mode == Mode.EDITOR) {
            renderWorkspaceList(graphics, mouseX, mouseY)
            renderEditor(graphics, mouseX, mouseY)
            renderStatusBar(graphics, mouseX, mouseY)
        }
        renderTooltip(graphics, mouseX, mouseY)
    }

    private fun toggleMode() {
        mode = if (mode == Mode.TERMINAL) Mode.EDITOR else Mode.TERMINAL
        getTerminal().visible = mode == Mode.TERMINAL
        if (mode == Mode.EDITOR) {
            requestListing(browserPath)
        }
        updateControls()
    }

    private fun updateControls() {
        modeButton.setMessage(Component.literal(if (mode == Mode.TERMINAL) "IDE" else "Term"))
        val editorVisible = mode == Mode.EDITOR
        saveButton.visible = editorVisible
        refreshButton.visible = editorVisible
        upButton.visible = editorVisible
        rebootButton.visible = editorVisible
        getTerminal().visible = !editorVisible
    }

    private fun syncWorkspaceState() {
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

    private fun requestListing(path: String) {
        browserPath = path.trim('/').trim()
        ClientNetworking.sendToServer(ComputerWorkspaceServerMessage(menu, ComputerWorkspaceServerMessage.Action.LIST, browserPath))
    }

    private fun requestDocument(path: String) {
        ClientNetworking.sendToServer(ComputerWorkspaceServerMessage(menu, ComputerWorkspaceServerMessage.Action.READ, path))
    }

    private fun saveDocument() {
        val path = openDocument?.path ?: return
        ClientNetworking.sendToServer(ComputerWorkspaceServerMessage(menu, ComputerWorkspaceServerMessage.Action.WRITE, path, editorText))
        editorDirty = false
        refreshIde()
    }

    private fun navigateUp() {
        browserPath = browserPath.substringBeforeLast('/', "")
        requestListing(browserPath)
    }

    private fun refreshIde() {
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

    private fun openCompletion() {
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

    private fun applyCompletion() {
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

    private fun navigateToDefinition() {
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
        keepCursorVisible()
    }

    private fun renderWorkspaceList(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        val font = minecraft!!.font
        graphics.drawString(font, Component.literal("/" + browserPath).visualOrderText, leftPos + 12, topPos + 38, 0xBFD5E8, false)
        var rowY = topPos + 54
        if (browserPath.isNotEmpty()) {
            drawWorkspaceRow(graphics, "..", rowY, mouseX, mouseY, true)
            rowY += 12
        }
        cachedEntries.forEach { entry ->
            drawWorkspaceRow(
                graphics,
                if (entry.directory) entry.path.substringAfterLast('/') + "/" else entry.path.substringAfterLast('/'),
                rowY,
                mouseX,
                mouseY,
                false,
            )
            rowY += 12
        }
    }

    private fun drawWorkspaceRow(
        graphics: GuiGraphics,
        label: String,
        rowY: Int,
        mouseX: Int,
        mouseY: Int,
        selected: Boolean,
    ) {
        val hovered = mouseX in (leftPos + 10)..(leftPos + 124) && mouseY in rowY..(rowY + 10)
        if (hovered || selected) {
            graphics.fill(leftPos + 10, rowY - 1, leftPos + 124, rowY + 10, 0x443F5F8F)
        }
        graphics.drawString(minecraft!!.font, label, leftPos + 14, rowY, 0xE6ECF5, false)
    }

    private fun renderEditor(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        val font = minecraft!!.font
        val lines = editorLines()
        val startLine = editorScrollLine.coerceAtLeast(0)
        val visibleLines = visibleEditorLines()
        val endLine = min(lines.size, startLine + visibleLines)
        var drawY = topPos + 40
        for (lineIndex in startLine until endLine) {
            val lineNumberX = leftPos + 142
            val contentX = leftPos + 176
            if (lineIndex == cursorLine) {
                graphics.fill(leftPos + 138, drawY - 1, leftPos + imageWidth - 10, drawY + 9, 0x33294055)
            }
            graphics.drawString(font, (lineIndex + 1).toString(), lineNumberX, drawY, 0x7D899C, false)
            renderHighlightedLine(graphics, lines[lineIndex], lineIndex, contentX, drawY)
            drawY += 10
        }
        renderCursor(graphics, lines)
        hoverInfo =
            if (isInEditorArea(mouseX, mouseY) && openDocument != null) {
                val position = mouseToCursor(mouseX, mouseY)
                ide.hover(openDocument!!.path, editorText, position.first, position.second)
            } else {
                null
            }
        if (completionItems.isNotEmpty()) {
            renderCompletionPopup(graphics)
        }
    }

    private fun renderStatusBar(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        val font = minecraft!!.font
        val path = openDocument?.path ?: "No file opened"
        val status = if (editorDirty) "* $path" else path
        graphics.drawString(font, status, leftPos + 140, topPos + imageHeight - 24, 0xE6ECF5, false)
        val hover =
            hoverInfo?.contents ?: ideSnapshot
                ?.diagnostics
                ?.firstOrNull()
                ?.message
                .orEmpty()
        if (hover.isNotEmpty()) {
            graphics.drawString(font, hover.take(60), leftPos + 140, topPos + imageHeight - 14, 0xE0A96D, false)
        }
    }

    private fun renderHighlightedLine(
        graphics: GuiGraphics,
        lineText: String,
        lineIndex: Int,
        x: Int,
        y: Int,
    ) {
        val font = minecraft!!.font
        val tokens = ideSnapshot?.highlights.orEmpty().filter { it.range.start.line == lineIndex && it.range.end.line == lineIndex }
        if (tokens.isEmpty()) {
            graphics.drawString(font, lineText, x, y, 0xE6ECF5, false)
            return
        }

        var drawX = x
        var column = 0
        tokens.sortedBy { it.range.start.column }.forEach { token ->
            val start =
                token.range.start.column
                    .coerceIn(0, lineText.length)
            val end =
                token.range.end.column
                    .coerceIn(start, lineText.length)
            if (start > column) {
                val plain = lineText.substring(column, start)
                graphics.drawString(font, plain, drawX, y, 0xE6ECF5, false)
                drawX += font.width(plain)
            }
            val colored = lineText.substring(start, end)
            graphics.drawString(font, colored, drawX, y, colorFor(token.kind), false)
            drawX += font.width(colored)
            column = end
        }
        if (column < lineText.length) {
            graphics.drawString(font, lineText.substring(column), drawX, y, 0xE6ECF5, false)
        }
    }

    private fun renderCursor(
        graphics: GuiGraphics,
        lines: List<String>,
    ) {
        val lineHeight = 10
        val visibleLine = cursorLine - editorScrollLine
        if (visibleLine < 0 || visibleLine >= visibleEditorLines()) return
        val beforeCursor = lines.getOrElse(cursorLine) { "" }.take(cursorColumn)
        val x = leftPos + 176 + minecraft!!.font.width(beforeCursor)
        val y = topPos + 40 + visibleLine * lineHeight
        if ((minecraft!!.gui.guiTicks / 6) % 2 == 0) {
            graphics.fill(x, y - 1, x + 1, y + 9, 0xFFFFFFFF.toInt())
        }
    }

    private fun renderCompletionPopup(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val lines = editorLines()
        val visibleLine = cursorLine - editorScrollLine
        if (visibleLine < 0) return
        val beforeCursor = lines.getOrElse(cursorLine) { "" }.take(cursorColumn)
        val popupX = leftPos + 176 + font.width(beforeCursor)
        val popupY = topPos + 52 + visibleLine * 10
        val items = completionItems.take(8)
        val width = items.maxOfOrNull { font.width(it.label + "  " + detailFor(it.kind)) }?.plus(12) ?: 120
        graphics.fill(popupX, popupY, popupX + width, popupY + items.size * 12 + 4, 0xEE11151E.toInt())
        items.forEachIndexed { index, item ->
            val rowY = popupY + 2 + index * 12
            if (index == selectedCompletion) {
                graphics.fill(popupX + 2, rowY - 1, popupX + width - 2, rowY + 10, 0x664883C7)
            }
            graphics.drawString(font, item.label, popupX + 6, rowY, 0xF5F7FA, false)
            graphics.drawString(font, detailFor(item.kind), popupX + width - 36, rowY, 0x9CA8B8, false)
        }
    }

    private fun handleFileListClick(
        mouseX: Int,
        mouseY: Int,
    ): Boolean {
        if (mouseX !in (leftPos + 10)..(leftPos + 124)) return false
        var rowY = topPos + 54
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

    private fun handleCompletionClick(
        mouseX: Int,
        mouseY: Int,
    ): Boolean {
        if (completionItems.isEmpty()) return false
        val font = minecraft!!.font
        val lines = editorLines()
        val visibleLine = cursorLine - editorScrollLine
        val beforeCursor = lines.getOrElse(cursorLine) { "" }.take(cursorColumn)
        val popupX = leftPos + 176 + font.width(beforeCursor)
        val popupY = topPos + 52 + visibleLine * 10
        if (mouseX < popupX || mouseX > popupX + 220 || mouseY < popupY || mouseY > popupY + 100) return false
        val index = ((mouseY - popupY - 2) / 12).coerceIn(0, completionItems.size - 1)
        selectedCompletion = index
        applyCompletion()
        return true
    }

    private fun isInEditorArea(
        mouseX: Int,
        mouseY: Int,
    ): Boolean = mouseX in (leftPos + 136)..(leftPos + imageWidth - 10) && mouseY in (topPos + 34)..(topPos + imageHeight - 32)

    private fun placeCursorAt(
        mouseX: Int,
        mouseY: Int,
    ) {
        val target = mouseToCursor(mouseX, mouseY)
        cursorLine = target.first
        cursorColumn = target.second
        keepCursorVisible()
    }

    private fun mouseToCursor(
        mouseX: Int,
        mouseY: Int,
    ): Pair<Int, Int> {
        val lines = editorLines()
        val lineIndex = ((mouseY - (topPos + 40)) / 10 + editorScrollLine).coerceIn(0, max(lines.lastIndex, 0))
        val line = lines.getOrElse(lineIndex) { "" }
        val relativeX = mouseX - (leftPos + 176)
        var bestColumn = 0
        var bestDistance = Int.MAX_VALUE
        for (index in 0..line.length) {
            val width = minecraft!!.font.width(line.take(index))
            val distance = kotlin.math.abs(relativeX - width)
            if (distance < bestDistance) {
                bestDistance = distance
                bestColumn = index
            }
        }
        return lineIndex to bestColumn
    }

    private fun editorLines(): List<String> = if (editorText.isEmpty()) listOf("") else editorText.split('\n')

    private fun visibleEditorLines(): Int = ((imageHeight - 82) / 10).coerceAtLeast(1)

    private fun moveCursorHorizontal(delta: Int) {
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
        keepCursorVisible()
    }

    private fun moveCursorVertical(delta: Int) {
        val lines = editorLines()
        cursorLine = (cursorLine + delta).coerceIn(0, lines.lastIndex)
        cursorColumn = cursorColumn.coerceIn(0, lines[cursorLine].length)
        keepCursorVisible()
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

    private fun insertText(text: String) {
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
        keepCursorVisible()
    }

    private fun keepCursorVisible() {
        if (cursorLine < editorScrollLine) {
            editorScrollLine = cursorLine
        }
        val maxVisible = editorScrollLine + visibleEditorLines() - 1
        if (cursorLine > maxVisible) {
            editorScrollLine = cursorLine - visibleEditorLines() + 1
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

    private fun detailFor(kind: CompletionItemKind): String =
        when (kind) {
            CompletionItemKind.KEYWORD -> "kw"
            CompletionItemKind.MODULE -> "mod"
            CompletionItemKind.FUNCTION -> "fn"
            CompletionItemKind.VARIABLE -> "var"
            CompletionItemKind.PARAMETER -> "arg"
            CompletionItemKind.TYPE -> "type"
            CompletionItemKind.FIELD -> "field"
        }

    private fun colorFor(kind: ck.lang.runtime.HighlightTokenKind): Int =
        when (kind) {
            ck.lang.runtime.HighlightTokenKind.KEYWORD -> 0x8EC5FF
            ck.lang.runtime.HighlightTokenKind.STRING -> 0xD9C27C
            ck.lang.runtime.HighlightTokenKind.NUMBER -> 0xC6A0F6
            ck.lang.runtime.HighlightTokenKind.BOOLEAN -> 0xC6A0F6
            ck.lang.runtime.HighlightTokenKind.NULL -> 0xC6A0F6
            ck.lang.runtime.HighlightTokenKind.IDENTIFIER -> 0xE6ECF5
            ck.lang.runtime.HighlightTokenKind.FUNCTION -> 0x8BD5CA
            ck.lang.runtime.HighlightTokenKind.TYPE -> 0xF5B971
            ck.lang.runtime.HighlightTokenKind.MODULE -> 0x7FC1FF
            ck.lang.runtime.HighlightTokenKind.FIELD -> 0xA8D68F
            ck.lang.runtime.HighlightTokenKind.OPERATOR -> 0xE6ECF5
            ck.lang.runtime.HighlightTokenKind.PUNCTUATION -> 0xB0B8C5
        }

    private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other
}
