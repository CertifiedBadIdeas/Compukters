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

import ck.mod.menu.AbstractComputerMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import kotlin.math.min

class ComputerWorkbenchKoolScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : KoolScreen<T>(container, player, title) {
    private val presenter = ComputerWorkbenchPresenter(container)
    private val terminalInput = WorkbenchTerminalInputController(presenter.input)

    init {
        imageWidth = WorkbenchTerminalMetrics.imageWidth(presenter.terminalData)
        imageHeight = WorkbenchTerminalMetrics.imageHeight(presenter.terminalData)
    }

    override fun init() {
        presenter.init()
        super.init()
    }

    override fun containerTick() {
        super.containerTick()
        presenter.tick()
        terminalInput.update()
    }

    override fun renderBg(
        graphics: GuiGraphics,
        partialTicks: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (presenter.mode == ComputerWorkbenchPresenter.Mode.TERMINAL) {
            WorkbenchTerminalRenderer.render(
                graphics,
                minecraft!!.font,
                leftPos,
                topPos,
                imageWidth,
                imageHeight,
                terminalLayout(),
                presenter.terminalData,
                terminalInput.focused,
            )
        } else {
            graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF12151D.toInt())
            graphics.fill(leftPos + 8, topPos + 34, leftPos + 128, topPos + imageHeight - 12, 0xFF1D2330.toInt())
            graphics.fill(leftPos + 136, topPos + 34, leftPos + imageWidth - 8, topPos + imageHeight - 32, 0xFF0D1016.toInt())
            graphics.fill(leftPos + 136, topPos + imageHeight - 28, leftPos + imageWidth - 8, topPos + imageHeight - 8, 0xFF161B25.toInt())
        }
        renderToolbar(graphics)
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        if (presenter.mode == ComputerWorkbenchPresenter.Mode.EDITOR && presenter.isInEditorArea(layout(), mouseX, mouseY)) {
            val position = presenter.mouseToCursor(layout(), minecraft!!.font, mouseX, mouseY)
            presenter.updateHover(position.first, position.second)
        } else {
            presenter.clearHover()
        }

        renderBackground(graphics)
        super.render(graphics, mouseX, mouseY, partialTicks)

        if (presenter.mode == ComputerWorkbenchPresenter.Mode.EDITOR) {
            renderWorkspaceList(graphics, mouseX, mouseY)
            renderEditor(graphics)
            renderStatusBar(graphics)
        }

        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun renderLabels(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
    }

    override fun keyPressed(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        val previousMode = presenter.mode
        if (presenter.mode == ComputerWorkbenchPresenter.Mode.TERMINAL && terminalInput.focused) {
            if (terminalInput.keyPressed(key, scancode, modifiers)) {
                return true
            }
        }
        if (presenter.keyPressed(key, scancode, modifiers, presenter.visibleEditorLines(layout()))) {
            if (previousMode != presenter.mode && presenter.mode != ComputerWorkbenchPresenter.Mode.TERMINAL) {
                terminalInput.focused = false
            }
            return true
        }
        return super.keyPressed(key, scancode, modifiers)
    }

    override fun keyReleased(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        if (presenter.mode == ComputerWorkbenchPresenter.Mode.TERMINAL && terminalInput.focused) {
            if (terminalInput.keyReleased(key, scancode)) {
                return true
            }
        }
        return super.keyReleased(key, scancode, modifiers)
    }

    override fun charTyped(
        ch: Char,
        modifiers: Int,
    ): Boolean {
        if (presenter.mode == ComputerWorkbenchPresenter.Mode.TERMINAL && terminalInput.focused) {
            return terminalInput.charTyped(ch)
        }
        if (presenter.charTyped(ch, presenter.visibleEditorLines(layout()))) {
            return true
        }
        return super.charTyped(ch, modifiers)
    }

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        if (button == 0 && handleToolbarClick(mouseX.toInt(), mouseY.toInt())) {
            return true
        }

        if (presenter.mode == ComputerWorkbenchPresenter.Mode.TERMINAL) {
            terminalInput.focused = terminalInput.mouseClicked(terminalLayout().terminalBounds, mouseX, mouseY)
            return terminalInput.focused || super.mouseClicked(mouseX, mouseY, button)
        }

        if (button == 0) {
            terminalInput.focused = false
            if (presenter.handleFileListClick(layout(), mouseX.toInt(), mouseY.toInt())) {
                return true
            }
            if (presenter.handleCompletionClick(layout(), minecraft!!.font, mouseX.toInt(), mouseY.toInt())) {
                return true
            }
            if (presenter.isInEditorArea(layout(), mouseX.toInt(), mouseY.toInt())) {
                presenter.placeCursorAt(layout(), minecraft!!.font, mouseX.toInt(), mouseY.toInt(), presenter.visibleEditorLines(layout()))
                return true
            }
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean = super.mouseReleased(mouseX, mouseY, button)

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double,
    ): Boolean = super.mouseDragged(mouseX, mouseY, button, dragX, dragY)

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        delta: Double,
    ): Boolean {
        if (presenter.mode == ComputerWorkbenchPresenter.Mode.EDITOR && presenter.isInEditorArea(layout(), mouseX.toInt(), mouseY.toInt())) {
            presenter.scrollEditor(-delta.toInt())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, delta)
    }

    private fun renderToolbar(graphics: GuiGraphics) {
        val buttons =
            listOf(
                toolbarButtonBounds(0) to if (presenter.mode == ComputerWorkbenchPresenter.Mode.TERMINAL) "IDE" else "Term",
                toolbarButtonBounds(1) to "Save",
                toolbarButtonBounds(2) to "Refresh",
                toolbarButtonBounds(3) to "Up",
                toolbarButtonBounds(4) to "Reboot",
            )

        buttons.forEach { (bounds, label) ->
            graphics.fill(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height, 0xFF222938.toInt())
            graphics.drawCenteredString(minecraft!!.font, label, bounds.x + bounds.width / 2, bounds.y + 7, 0xE6ECF5)
        }
    }

    private fun renderWorkspaceList(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        val font = minecraft!!.font
        graphics.drawString(font, Component.literal("/" + presenter.browserPath).visualOrderText, leftPos + 12, topPos + 38, 0xBFD5E8, false)
        var rowY = topPos + 54
        if (presenter.browserPath.isNotEmpty()) {
            drawWorkspaceRow(graphics, "..", rowY, mouseX, mouseY, true)
            rowY += 12
        }
        presenter.cachedEntries.forEach { entry ->
            val label = if (entry.directory) entry.path.substringAfterLast('/') + "/" else entry.path.substringAfterLast('/')
            drawWorkspaceRow(graphics, label, rowY, mouseX, mouseY, false)
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

    private fun renderEditor(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val lines = presenter.editorLines()
        val startLine = presenter.editorScrollLine.coerceAtLeast(0)
        val visibleLines = presenter.visibleEditorLines(layout())
        val endLine = min(lines.size, startLine + visibleLines)
        var drawY = topPos + 40

        for (lineIndex in startLine until endLine) {
            if (lineIndex == presenter.cursorLine) {
                graphics.fill(leftPos + 138, drawY - 1, leftPos + imageWidth - 10, drawY + 9, 0x33294055)
            }
            graphics.drawString(font, (lineIndex + 1).toString(), leftPos + 142, drawY, 0x7D899C, false)
            renderHighlightedLine(graphics, lines[lineIndex], lineIndex, leftPos + 176, drawY)
            drawY += ComputerWorkbenchPresenter.LINE_HEIGHT
        }

        renderCursor(graphics, lines)
        if (presenter.completionItems.isNotEmpty()) {
            renderCompletionPopup(graphics)
        }
    }

    private fun renderStatusBar(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val path = presenter.openDocument?.path ?: "No file opened"
        val status = if (presenter.editorDirty) "* $path" else path
        graphics.drawString(font, status, leftPos + 140, topPos + imageHeight - 24, 0xE6ECF5, false)
        val hover = presenter.hoverInfo?.contents ?: presenter.ideSnapshot?.diagnostics?.firstOrNull()?.message.orEmpty()
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
        val tokens = presenter.ideSnapshot?.highlights.orEmpty().filter { it.range.start.line == lineIndex && it.range.end.line == lineIndex }
        if (tokens.isEmpty()) {
            graphics.drawString(font, lineText, x, y, 0xE6ECF5, false)
            return
        }

        var drawX = x
        var column = 0
        tokens.sortedBy { it.range.start.column }.forEach { token ->
            val start = token.range.start.column.coerceIn(0, lineText.length)
            val end = token.range.end.column.coerceIn(start, lineText.length)
            if (start > column) {
                val plain = lineText.substring(column, start)
                graphics.drawString(font, plain, drawX, y, 0xE6ECF5, false)
                drawX += font.width(plain)
            }
            val colored = lineText.substring(start, end)
            graphics.drawString(font, colored, drawX, y, presenter.highlightColor(token.kind), false)
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
        val visibleLine = presenter.cursorLine - presenter.editorScrollLine
        if (visibleLine < 0 || visibleLine >= presenter.visibleEditorLines(layout())) {
            return
        }
        val beforeCursor = lines.getOrElse(presenter.cursorLine) { "" }.take(presenter.cursorColumn)
        val x = leftPos + 176 + minecraft!!.font.width(beforeCursor)
        val y = topPos + 40 + visibleLine * ComputerWorkbenchPresenter.LINE_HEIGHT
        if ((minecraft!!.gui.guiTicks / 6) % 2 == 0) {
            graphics.fill(x, y - 1, x + 1, y + 9, 0xFFFFFFFF.toInt())
        }
    }

    private fun renderCompletionPopup(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val lines = presenter.editorLines()
        val visibleLine = presenter.cursorLine - presenter.editorScrollLine
        if (visibleLine < 0) return

        val beforeCursor = lines.getOrElse(presenter.cursorLine) { "" }.take(presenter.cursorColumn)
        val popupX = leftPos + 176 + font.width(beforeCursor)
        val popupY = topPos + 52 + visibleLine * ComputerWorkbenchPresenter.LINE_HEIGHT
        val items = presenter.completionItems.take(8)
        val width = items.maxOfOrNull { font.width(it.label + "  " + presenter.completionDetail(it.kind)) }?.plus(12) ?: 120
        graphics.fill(popupX, popupY, popupX + width, popupY + items.size * 12 + 4, 0xEE11151E.toInt())
        items.forEachIndexed { index, item ->
            val rowY = popupY + 2 + index * 12
            if (index == presenter.selectedCompletion) {
                graphics.fill(popupX + 2, rowY - 1, popupX + width - 2, rowY + 10, 0x664883C7)
            }
            graphics.drawString(font, item.label, popupX + 6, rowY, 0xF5F7FA, false)
            graphics.drawString(font, presenter.completionDetail(item.kind), popupX + width - 36, rowY, 0x9CA8B8, false)
        }
    }

    private fun handleToolbarClick(
        mouseX: Int,
        mouseY: Int,
    ): Boolean {
        repeat(5) { index ->
            val bounds = toolbarButtonBounds(index)
            if (mouseX in bounds.x..(bounds.x + bounds.width) && mouseY in bounds.y..(bounds.y + bounds.height)) {
                when (index) {
                    0 -> {
                        presenter.toggleMode()
                        if (presenter.mode != ComputerWorkbenchPresenter.Mode.TERMINAL) {
                            terminalInput.focused = false
                        }
                    }
                    1 -> presenter.saveDocument()
                    2 -> {
                        presenter.requestListing(presenter.browserPath)
                        presenter.openDocument?.path?.let(presenter::requestDocument)
                    }
                    3 -> presenter.navigateUp()
                    4 -> presenter.input.reboot()
                }
                return true
            }
        }
        return false
    }

    private fun toolbarButtonBounds(index: Int): ButtonBounds = ButtonBounds(leftPos + 8 + index * 80, topPos + 8, 72, 20)

    private fun layout(): ComputerWorkbenchLayout = ComputerWorkbenchLayout(leftPos, topPos, imageWidth, imageHeight)

    private fun terminalLayout(): WorkbenchTerminalLayout =
        WorkbenchTerminalMetrics.layout(
            leftPos,
            topPos,
            imageWidth,
            imageHeight,
            presenter.terminalData,
        )

    private data class ButtonBounds(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )
}
