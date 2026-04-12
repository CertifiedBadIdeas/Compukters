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
package ru.lazyhat.compukterkraft.common.gui.screen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.gui.input.ClientInputHandler
import ru.lazyhat.compukterkraft.common.infrastructure.coroutines.minecraft
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.InputHandlerControlGateway
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.LanguageWorkbenchIdeFacade
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.MenuWorkspaceUpdateSource
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.NetworkWorkspaceGateway
import ru.lazyhat.compukterkraft.common.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.platform.MinecraftInputProvider
import ru.lazyhat.compukterkraft.common.ui.render.WorkbenchTerminalRenderer
import ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchMode
import ru.lazyhat.compukterkraft.core.application.workbench.WorkbenchStore
import ru.lazyhat.compukterkraft.core.application.workbench.completionDetail
import ru.lazyhat.compukterkraft.core.application.workbench.highlightColor
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalInputController
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalLayout
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicy
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModel
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkspaceRowLayout
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewState
import kotlin.math.min

class ComputerWorkbenchScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : ComputerScreen<T>(container, player, title) {
    private val inputHandler = ClientInputHandler(container)
    private val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)
    private val store =
        WorkbenchStore(
            workspaceGateway = NetworkWorkspaceGateway(container),
            controlGateway = InputHandlerControlGateway(inputHandler),
            ideFacade = LanguageWorkbenchIdeFacade,
        )
    private var screenScope: CoroutineScope? = null

    init {
        val (terminalColumns, terminalRows) = terminalDimensions(container.clientSide.screenSnapshot)
        imageWidth = WorkbenchTerminalMetrics.imageWidth(terminalColumns, terminalRows)
        imageHeight = WorkbenchTerminalMetrics.imageHeight(terminalColumns, terminalRows)
    }

    override fun init() {
        super.init()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.minecraft)
        screenScope = scope
        store.bind(scope, MenuWorkspaceUpdateSource(menu))
        store.initialize()
    }

    override fun removed() {
        store.dispose()
        screenScope?.cancel()
        screenScope = null
        super.removed()
    }

    override fun containerTick() {
        super.containerTick()
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (terminalState !is WorkbenchTerminalViewState.Active && terminalInput.focused) {
            terminalInput.focused = false
        }
        syncTerminalWindowSize(terminalState)
        terminalInput.update()
    }

    override fun renderBg(
        graphics: GuiGraphics,
        partialTicks: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (store.state.mode == WorkbenchMode.TERMINAL) {
            val snapshot = menu.clientSide.screenSnapshot
            val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, snapshot)
            val focused = WorkbenchTerminalInteractionPolicy.canAcceptInput(store.state.mode, terminalState, terminalInput.focused)
            val showFocusHint = WorkbenchTerminalInteractionPolicy.showFocusHint(terminalState, terminalInput.focused)
            WorkbenchTerminalRenderer.render(
                graphics,
                minecraft!!.font,
                leftPos,
                topPos,
                imageWidth,
                imageHeight,
                terminalLayout(),
                terminalState,
                focused,
                showFocusHint,
                Component.translatable("gui.compukterkraft.terminal.powered_off").string,
                Component.translatable("gui.compukterkraft.terminal.connecting").string,
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
        val layout = layout()
        if (store.state.mode == WorkbenchMode.EDITOR && layout.editorBounds.contains(mouseX, mouseY)) {
            val position = layout.mouseToCursor(store.state, mouseX, mouseY)
            store.updateHover(position.first, position.second)
        } else {
            store.clearHover()
        }

        renderBackground(graphics, mouseX, mouseY, partialTicks)
        super.render(graphics, mouseX, mouseY, partialTicks)

        if (store.state.mode == WorkbenchMode.EDITOR) {
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
        val previousMode = store.state.mode
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (WorkbenchTerminalInteractionPolicy.canAcceptInput(store.state.mode, terminalState, terminalInput.focused)) {
            if (terminalInput.keyPressed(key, scancode, modifiers)) {
                return true
            }
        }

        if (store.keyPressed(key, modifiers, layout().visibleEditorLines())) {
            if (previousMode != store.state.mode && store.state.mode != WorkbenchMode.TERMINAL) {
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
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (WorkbenchTerminalInteractionPolicy.canAcceptInput(store.state.mode, terminalState, terminalInput.focused)) {
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
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (WorkbenchTerminalInteractionPolicy.canAcceptInput(store.state.mode, terminalState, terminalInput.focused)) {
            return terminalInput.charTyped(ch)
        }
        if (store.charTyped(ch, layout().visibleEditorLines())) {
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

        if (store.state.mode == WorkbenchMode.TERMINAL) {
            val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
            if (terminalState is WorkbenchTerminalViewState.Active) {
                terminalInput.focused = terminalInput.mouseClicked(terminalLayout().terminalBounds, mouseX, mouseY)
            } else {
                terminalInput.focused = false
            }
            return terminalInput.focused || super.mouseClicked(mouseX, mouseY, button)
        }

        val layout = layout()
        if (button == 0) {
            terminalInput.focused = false

            layout.workspaceRowAt(store.state, mouseX.toInt(), mouseY.toInt())?.let { row ->
                handleWorkspaceRow(row)
                return true
            }

            layout.completionIndexAt(store.state, mouseX.toInt(), mouseY.toInt())?.let { index ->
                store.applyCompletion(index)
                return true
            }

            if (layout.editorBounds.contains(mouseX.toInt(), mouseY.toInt())) {
                val target = layout.mouseToCursor(store.state, mouseX.toInt(), mouseY.toInt())
                store.moveCursorTo(target.first, target.second, layout.visibleEditorLines())
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
        horizontalAmount: Double,
        verticalAmount: Double,
    ): Boolean {
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (store.state.mode == WorkbenchMode.TERMINAL && terminalState is WorkbenchTerminalViewState.Active) {
            if (terminalInput.mouseScrolled(terminalLayout().terminalBounds, mouseX, mouseY, verticalAmount)) {
                return true
            }
        }
        val layout = layout()
        if (store.state.mode == WorkbenchMode.EDITOR && layout.editorBounds.contains(mouseX.toInt(), mouseY.toInt())) {
            store.scrollEditor(-verticalAmount.toInt())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private fun renderToolbar(graphics: GuiGraphics) {
        layout().toolbarButtons(store.state).forEach { button ->
            val bounds = button.bounds
            graphics.fill(bounds.x, bounds.y, bounds.right, bounds.bottom, 0xFF222938.toInt())
            graphics.drawCenteredString(minecraft!!.font, button.label, bounds.x + bounds.width / 2, bounds.y + 7, 0xE6ECF5)
        }
    }

    private fun renderWorkspaceList(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        val font = minecraft!!.font
        graphics.drawString(
            font,
            Component.literal("/" + store.state.browserPath).visualOrderText,
            leftPos + 12,
            topPos + 38,
            0xBFD5E8,
            false,
        )
        layout().workspaceRows(store.state).forEach { row ->
            drawWorkspaceRow(graphics, row, mouseX, mouseY)
        }
    }

    private fun drawWorkspaceRow(
        graphics: GuiGraphics,
        row: WorkspaceRowLayout,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = row.bounds.contains(mouseX, mouseY)
        if (hovered || row.selected) {
            graphics.fill(row.bounds.x, row.bounds.y, row.bounds.right, row.bounds.bottom, 0x443F5F8F)
        }
        graphics.drawString(minecraft!!.font, row.label, row.bounds.x + 4, row.bounds.y + 1, 0xE6ECF5, false)
    }

    private fun renderEditor(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val lines = editorLines()
        val startLine =
            store.state.editor.scrollLine
                .coerceAtLeast(0)
        val visibleLines = layout().visibleEditorLines()
        val endLine = min(lines.size, startLine + visibleLines)
        var drawY = topPos + 40

        val bounds = layout().editorBounds
        graphics.enableScissor(bounds.x, bounds.y, bounds.right, bounds.bottom)

        for (lineIndex in startLine until endLine) {
            if (lineIndex == store.state.editor.cursorLine) {
                graphics.fill(leftPos + 138, drawY - 1, leftPos + imageWidth - 10, drawY + 9, 0x33294055)
            }
            graphics.drawString(font, (lineIndex + 1).toString(), leftPos + 142, drawY, 0x7D899C, false)
            renderHighlightedLine(graphics, lines[lineIndex], lineIndex, leftPos + 176, drawY)
            drawY += layout().editorLineHeight
        }

        renderCursor(graphics, lines)

        graphics.disableScissor()

        if (store.state.editor.completionItems
                .isNotEmpty()
        ) {
            renderCompletionPopup(graphics)
        }
    }

    private fun renderStatusBar(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val path = store.state.openDocument?.path ?: "No file opened"
        val status = if (store.state.editor.dirty) "* $path" else path
        graphics.drawString(font, status, leftPos + 140, topPos + imageHeight - 24, 0xE6ECF5, false)
        val hover =
            store.state.editor.hoverInfo
                ?.contents ?: store.state.editor.ideSnapshot
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
        val tokens =
            store.state.editor.ideSnapshot
                ?.highlights
                .orEmpty()
                .filter { it.range.start.line == lineIndex && it.range.end.line == lineIndex }
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
            graphics.drawString(font, colored, drawX, y, highlightColor(token.kind), false)
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
        val visibleLine = store.state.editor.cursorLine - store.state.editor.scrollLine
        if (visibleLine < 0 || visibleLine >= layout().visibleEditorLines()) {
            return
        }
        val beforeCursor = lines.getOrElse(store.state.editor.cursorLine) { "" }.take(store.state.editor.cursorColumn)
        val x = leftPos + 176 + minecraft!!.font.width(beforeCursor)
        val y = topPos + 40 + visibleLine * layout().editorLineHeight
        if ((minecraft!!.gui.guiTicks / 6) % 2 == 0) {
            graphics.fill(x, y - 1, x + 1, y + 9, 0xFFFFFFFF.toInt())
        }
    }

    private fun renderCompletionPopup(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val popup = layout().completionPopup(store.state) ?: return
        val items =
            store.state.editor.completionItems
                .take(popup.visibleItems)
        graphics.fill(popup.bounds.x, popup.bounds.y, popup.bounds.right, popup.bounds.bottom, 0xEE11151E.toInt())
        items.forEachIndexed { index, item ->
            val rowY = popup.bounds.y + 2 + index * popup.rowHeight
            if (index == store.state.editor.selectedCompletion) {
                graphics.fill(popup.bounds.x + 2, rowY - 1, popup.bounds.right - 2, rowY + 10, 0x664883C7)
            }
            graphics.drawString(font, item.label, popup.bounds.x + 6, rowY, 0xF5F7FA, false)
            graphics.drawString(font, completionDetail(item.kind), popup.bounds.right - 36, rowY, 0x9CA8B8, false)
        }
    }

    private fun handleToolbarClick(
        mouseX: Int,
        mouseY: Int,
    ): Boolean {
        layout().toolbarButtonAt(store.state, mouseX, mouseY)?.let { button ->
            when (button.index) {
                0 -> {
                    store.toggleMode()
                    if (store.state.mode != WorkbenchMode.TERMINAL) {
                        terminalInput.focused = false
                    }
                }

                1 -> {
                    store.saveDocument()
                }

                2 -> {
                    store.refreshWorkspace()
                }

                3 -> {
                    store.navigateUp()
                }

                4 -> {
                    store.rebootComputer()
                }
            }
            return true
        }
        return false
    }

    private fun handleWorkspaceRow(row: WorkspaceRowLayout) {
        val path = row.path
        when {
            path == null -> store.navigateUp()
            row.directory -> store.requestListing(path)
            else -> store.requestDocument(path)
        }
    }

    private fun layout(): WorkbenchLayoutModel =
        WorkbenchLayoutModel(
            leftPos,
            topPos,
            imageWidth,
            imageHeight,
        ) {
            minecraft!!.font.width(it)
        }

    private fun terminalLayout(): WorkbenchTerminalLayout {
        val (terminalColumns, terminalRows) = terminalDimensions(menu.clientSide.screenSnapshot)
        return WorkbenchTerminalMetrics.layout(
            leftPos,
            topPos,
            imageWidth,
            imageHeight,
            terminalColumns,
            terminalRows,
        )
    }

    private fun syncTerminalWindowSize(terminalState: WorkbenchTerminalViewState) {
        val (terminalColumns, terminalRows) =
            when (terminalState) {
                is WorkbenchTerminalViewState.Active -> terminalDimensions(terminalState.snapshot)
                WorkbenchTerminalViewState.PoweredOff, WorkbenchTerminalViewState.Connecting -> 0 to 0
            }

        val nextWidth = WorkbenchTerminalMetrics.imageWidth(terminalColumns, terminalRows)
        val nextHeight = WorkbenchTerminalMetrics.imageHeight(terminalColumns, terminalRows)
        if (imageWidth != nextWidth || imageHeight != nextHeight) {
            imageWidth = nextWidth
            imageHeight = nextHeight
            leftPos = (width - imageWidth) / 2
            topPos = (height - imageHeight) / 2
        }
    }

    private fun terminalDimensions(snapshot: ScreenBufferSnapshot?): Pair<Int, Int> =
        if (snapshot == null) {
            0 to 0
        } else {
            snapshot.width to snapshot.height
        }

    private fun editorLines(): List<String> =
        if (store.state.editor.text
                .isEmpty()
        ) {
            listOf("")
        } else {
            store.state.editor.text
                .split('\n')
        }
}
