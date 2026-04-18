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

package ru.lazyhat.compukterkraft.common.workbench.screen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.infrastructure.coroutines.minecraft
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.LanguageWorkbenchIdeFacade
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.MenuWorkspaceUpdateSource
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.NetworkWorkbenchControlGateway
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.NetworkWorkbenchWorkspaceGateway
import ru.lazyhat.compukterkraft.common.infrastructure.workbench.WorkbenchTargetCatalogSource
import ru.lazyhat.compukterkraft.common.platform.MinecraftInputProvider
import ru.lazyhat.compukterkraft.common.ui.render.WorkbenchTerminalRenderer
import ru.lazyhat.compukterkraft.common.workbench.input.WorkbenchClientInputHandler
import ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchMenuWithoutInventory
import ru.lazyhat.compukterkraft.common.workbench.menu.WorkbenchPositionableSlot
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchMode
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStore
import ru.lazyhat.compukterkraft.core.computer.workbench.completionDetail
import ru.lazyhat.compukterkraft.core.computer.workbench.highlightColor
import ru.lazyhat.compukterkraft.core.gui.TerminalFontConstants
import ru.lazyhat.compukterkraft.core.gui.TerminalRect
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalInputController
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalLayout
import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import ru.lazyhat.compukterkraft.core.ui.workbench.ToolbarButtonLayout
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchLayoutModel
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicy
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewState
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkspaceRowLayout
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import kotlin.math.min

class WorkbenchEditorScreen(
    container: WorkbenchMenuWithoutInventory,
    player: Inventory,
    title: Component,
) : AbstractContainerScreen<WorkbenchMenuWithoutInventory>(container, player, title) {
    private val inputHandler = WorkbenchClientInputHandler(container)
    private val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)
    private val store =
        WorkbenchStore(
            workspaceGateway = NetworkWorkbenchWorkspaceGateway(container),
            controlGateway = NetworkWorkbenchControlGateway(container),
            ideFacade = LanguageWorkbenchIdeFacade(WorkbenchTargetCatalogSource(container.workspaceStateFlow.value.target)),
        )
    private var screenScope: CoroutineScope? = null

    override fun init() {
        imageWidth = width
        imageHeight = height
        leftPos = 0
        topPos = 0
        super.init()
        leftPos = 0
        topPos = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.minecraft)
        screenScope = scope
        store.bind(scope, MenuWorkspaceUpdateSource(menu.workspaceStateFlow))
        if (store.state.mode != WorkbenchMode.EDITOR) {
            store.toggleMode()
        }
        store.initialize()
        syncSlotPositions()
    }

    override fun removed() {
        store.dispose()
        screenScope?.cancel()
        screenScope = null
        super.removed()
    }

    override fun containerTick() {
        super.containerTick()
        val terminalState = WorkbenchTerminalViewState.from(store.state.target.connected, menu.screenSnapshot)
        if (!store.state.terminalVisible || terminalState !is WorkbenchTerminalViewState.Active) {
            terminalInput.focused = false
        }
        syncFullscreenWindowSize()
        syncSlotPositions()
        terminalInput.update()
    }

    override fun renderBg(
        graphics: GuiGraphics,
        partialTicks: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        val layout = layout()
        val terminalState = WorkbenchTerminalViewState.from(store.state.target.connected, menu.screenSnapshot)
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF0B0E14.toInt())
        graphics.fill(
            layout.headerBounds.x,
            layout.headerBounds.y,
            layout.headerBounds.right,
            layout.headerBounds.bottom,
            0xFF161B25.toInt(),
        )
        graphics.fill(
            layout.sidebarBounds.x,
            layout.sidebarBounds.y,
            layout.sidebarBounds.right,
            layout.sidebarBounds.bottom,
            0xFF1D2330.toInt(),
        )
        graphics.fill(
            layout.editorBounds.x,
            layout.editorBounds.y,
            layout.editorBounds.right,
            layout.editorBounds.bottom,
            0xFF0D1016.toInt(),
        )
        graphics.fill(
            layout.inventoryBounds.x,
            layout.inventoryBounds.y,
            layout.inventoryBounds.right,
            layout.inventoryBounds.bottom,
            0xFF12161F.toInt(),
        )
        graphics.fill(
            layout.statusBarBounds.x,
            layout.statusBarBounds.y,
            layout.statusBarBounds.right,
            layout.statusBarBounds.bottom,
            0xFF161B25.toInt(),
        )
        if (store.state.terminalVisible) {
            val focused = terminalAcceptsInput(terminalState)
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
                drawWindowBackground = false,
            )
        }
        renderToolbar(graphics)
        renderHeader(graphics)
        renderInventoryChrome(graphics)
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        val layout = layout()
        if (layout.editorBounds.contains(mouseX, mouseY)) {
            val position = layout.mouseToCursor(store.state, mouseX, mouseY)
            store.updateHover(position.first, position.second)
        } else {
            store.clearHover()
        }

        renderBackground(graphics, mouseX, mouseY, partialTicks)
        super.render(graphics, mouseX, mouseY, partialTicks)

        renderWorkspaceList(graphics, mouseX, mouseY)
        renderEditor(graphics)
        renderStatusBar(graphics)

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
        val terminalState = WorkbenchTerminalViewState.from(store.state.target.connected, menu.screenSnapshot)
        if (terminalAcceptsInput(terminalState)) {
            if (terminalInput.keyPressed(key, scancode, modifiers)) {
                return true
            }
        }

        if (store.keyPressed(key, modifiers, layout().visibleEditorLines())) {
            if (!store.state.terminalVisible) {
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
        val terminalState = WorkbenchTerminalViewState.from(store.state.target.connected, menu.screenSnapshot)
        if (terminalAcceptsInput(terminalState)) {
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
        val terminalState = WorkbenchTerminalViewState.from(store.state.target.connected, menu.screenSnapshot)
        if (terminalAcceptsInput(terminalState)) {
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

        val terminalState = WorkbenchTerminalViewState.from(store.state.target.connected, menu.screenSnapshot)
        if (store.state.terminalVisible) {
            if (terminalState is WorkbenchTerminalViewState.Active &&
                terminalLayout().terminalBounds.contains(mouseX.toInt(), mouseY.toInt())
            ) {
                terminalInput.focused = terminalInput.mouseClicked(terminalLayout().terminalBounds, mouseX, mouseY)
                return terminalInput.focused || super.mouseClicked(mouseX, mouseY, button)
            }
            terminalInput.focused = false
        }

        val layout = layout()
        if (button == 0) {
            terminalInput.focused = false

            layout.importPickerIndexAt(store.state, mouseX.toInt(), mouseY.toInt())?.let { index ->
                store.applyImportPickerSelection(index, visibleEditorLines = layout.visibleEditorLines())
                return true
            }

            if (store.state.editor.importPickerVisible &&
                layout.importPickerPopup(store.state)?.bounds?.contains(mouseX.toInt(), mouseY.toInt()) == false
            ) {
                store.closeImportPicker()
                return true
            }

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
        val terminalState = WorkbenchTerminalViewState.from(store.state.target.connected, menu.screenSnapshot)
        if (store.state.terminalVisible && terminalState is WorkbenchTerminalViewState.Active) {
            if (terminalInput.mouseScrolled(terminalLayout().terminalBounds, mouseX, mouseY, verticalAmount)) {
                return true
            }
        }
        val layout = layout()
        if (layout.editorBounds.contains(mouseX.toInt(), mouseY.toInt())) {
            store.scrollEditor(-verticalAmount.toInt())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private fun renderToolbar(graphics: GuiGraphics) {
        workbenchToolbarButtons().forEach { button ->
            val bounds = button.bounds
            val disabled = button.index in 2..4 && !store.state.target.connected
            graphics.fill(bounds.x, bounds.y, bounds.right, bounds.bottom, if (disabled) 0xFF1B202A.toInt() else 0xFF222938.toInt())
            graphics.drawCenteredString(
                minecraft!!.font,
                button.label,
                bounds.x + bounds.width / 2,
                bounds.y + 7,
                if (disabled) 0x6F7C8C else 0xE6ECF5.toInt(),
            )
        }

        val layout = layout()
        graphics.fill(
            layout.terminalToggleBounds.x,
            layout.terminalToggleBounds.y,
            layout.terminalToggleBounds.right,
            layout.terminalToggleBounds.bottom,
            if (store.state.terminalVisible) 0xFF35516B.toInt() else 0xFF222938.toInt(),
        )
        graphics.drawCenteredString(
            minecraft!!.font,
            if (store.state.terminalVisible) "Hide" else "Terminal",
            layout.terminalToggleBounds.x + layout.terminalToggleBounds.width / 2,
            layout.terminalToggleBounds.y + 7,
            0xE6ECF5.toInt(),
        )

        val rebootDisabled = !store.state.target.connected
        graphics.fill(
            layout.rebootBounds.x,
            layout.rebootBounds.y,
            layout.rebootBounds.right,
            layout.rebootBounds.bottom,
            if (rebootDisabled) 0xFF1B202A.toInt() else 0xFF5A2A2A.toInt(),
        )
        graphics.drawCenteredString(
            minecraft!!.font,
            "Reboot",
            layout.rebootBounds.x + layout.rebootBounds.width / 2,
            layout.rebootBounds.y + 7,
            if (rebootDisabled) 0x6F7C8C else 0xFFF1E7E7.toInt(),
        )
    }

    private fun workbenchToolbarButtons(): List<ToolbarButtonLayout> =
        layout().toolbarButtons(store.state).filter { it.index in 1..5 }.map { button ->
            val label =
                when (button.index) {
                    1 -> "Save"
                    2 -> "Pull"
                    3 -> "Push"
                    4 -> "Run"
                    5 -> "Imports"
                    else -> button.label
                }
            button.copy(label = label)
        }

    private fun renderHeader(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val layout = layout()
        val slotX = menu.slots.firstOrNull()?.x ?: layout.targetSlotBounds.x
        val slotY = menu.slots.firstOrNull()?.y ?: layout.targetSlotBounds.y
        val targetLabel = store.state.target.displayName ?: "No target computer"
        val targetColor = if (store.state.target.connected) 0xE6ECF5.toInt() else 0x8A97A8
        graphics.drawString(font, title, layout.headerBounds.x + 12, layout.headerBounds.y + 12, 0xF5F7FA.toInt(), false)
        graphics.drawString(font, targetLabel, slotX + 24, slotY + 5, targetColor, false)
    }

    private fun renderInventoryChrome(graphics: GuiGraphics) {
        val layout = layout()
        val bounds = layout.inventoryBounds
        graphics.fill(bounds.x, bounds.y, bounds.right, bounds.y + 1, 0xFF2A3242.toInt())
        graphics.drawString(minecraft!!.font, "Inventory", bounds.x + 6, bounds.y - 10, 0x8A97A8, false)
    }

    private fun renderWorkspaceList(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        val font = minecraft!!.font
        val layout = layout()
        graphics.drawString(
            font,
            Component.literal("/" + store.state.browserPath).visualOrderText,
            layout.sidebarBounds.x + 4,
            layout.sidebarBounds.y + 6,
            0xBFD5E8,
            false,
        )
        layout.workspaceRows(store.state).forEach { row ->
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
        val layout = layout()
        val editorOrigin = layout.editorTextOrigin()
        val lines = editorLines()
        val startLine =
            store.state.editor.scrollLine
                .coerceAtLeast(0)
        val visibleLines = layout.visibleEditorLines()
        val endLine = min(lines.size, startLine + visibleLines)
        var drawY = editorOrigin.second

        val bounds = layout.editorBounds
        graphics.enableScissor(bounds.x, bounds.y, bounds.right, bounds.bottom)

        for (lineIndex in startLine until endLine) {
            if (lineIndex == store.state.editor.cursorLine) {
                graphics.fill(bounds.x + 2, drawY - 1, bounds.right - 2, drawY + 9, 0x33294055)
            }
            graphics.drawString(font, (lineIndex + 1).toString(), bounds.x + 6, drawY, 0x7D899C, false)
            renderHighlightedLine(graphics, lines[lineIndex], lineIndex, editorOrigin.first, drawY)
            drawY += layout.editorLineHeight
        }

        renderCursor(graphics, lines)

        graphics.disableScissor()

        if (store.state.editor.completionItems
                .isNotEmpty()
        ) {
            renderCompletionPopup(graphics)
        }

        if (store.state.editor.importPickerVisible) {
            renderImportPickerPopup(graphics)
        }
    }

    private fun renderStatusBar(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val bounds = layout().statusBarBounds
        val path = store.state.openDocument?.path ?: "No file opened"
        val status = if (store.state.editor.dirty) "* $path" else path
        graphics.drawString(font, status, bounds.x + 6, bounds.y + 6, 0xE6ECF5, false)
        val hover =
            store.state.editor.hoverInfo
                ?.contents ?: store.state.editor.ideSnapshot
                ?.diagnostics
                ?.firstOrNull()
                ?.message
                .orEmpty()
        if (hover.isNotEmpty()) {
            graphics.drawString(font, hover.take(96), bounds.x + 180, bounds.y + 6, 0xE0A96D, false)
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
        val layout = layout()
        if (visibleLine < 0 || visibleLine >= layout.visibleEditorLines()) {
            return
        }
        val beforeCursor = lines.getOrElse(store.state.editor.cursorLine) { "" }.take(store.state.editor.cursorColumn)
        val editorOrigin = layout.editorTextOrigin()
        val x = editorOrigin.first + minecraft!!.font.width(beforeCursor)
        val y = editorOrigin.second + visibleLine * layout.editorLineHeight
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

    private fun renderImportPickerPopup(graphics: GuiGraphics) {
        val font = minecraft!!.font
        val popup = layout().importPickerPopup(store.state) ?: return
        val items =
            store.state.editor.importPickerItems
                .take(popup.visibleItems)

        graphics.fill(popup.bounds.x, popup.bounds.y, popup.bounds.right, popup.bounds.bottom, 0xF0121721.toInt())
        graphics.fill(popup.bounds.x, popup.bounds.y, popup.bounds.right, popup.bounds.y + 14, 0xFF1F2937.toInt())
        graphics.drawString(font, "Available imports", popup.bounds.x + 6, popup.bounds.y + 3, 0xF5F7FA, false)

        items.forEachIndexed { index, item ->
            val rowY = popup.bounds.y + 18 + index * popup.rowHeight
            if (index == store.state.editor.selectedImportPickerIndex) {
                graphics.fill(popup.bounds.x + 2, rowY - 1, popup.bounds.right - 2, rowY + 10, 0x664883C7)
            }
            graphics.drawString(font, item.label, popup.bounds.x + 8, rowY, 0xF5F7FA, false)
        }
    }

    private fun handleToolbarClick(
        mouseX: Int,
        mouseY: Int,
    ): Boolean {
        val layout = layout()
        if (layout.terminalToggleBounds.contains(mouseX, mouseY)) {
            store.toggleTerminalVisibility()
            if (!store.state.terminalVisible) {
                terminalInput.focused = false
            }
            return true
        }
        if (layout.rebootBounds.contains(mouseX, mouseY)) {
            if (store.state.target.connected) {
                store.rebootComputer()
            }
            return true
        }
        workbenchToolbarButtons().firstOrNull { it.bounds.contains(mouseX, mouseY) }?.let { button ->
            when (button.index) {
                1 -> store.saveDocument()
                2 -> store.pullFromTarget()
                3 -> store.pushToTarget()
                4 -> store.runTargetProgram()
                5 -> store.openImportPicker()
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
        WorkbenchLayoutModel.fullscreen(
            leftPos,
            topPos,
            imageWidth,
            imageHeight,
            store.state.terminalVisible,
            FontMetrics { text -> minecraft!!.font.width(text) },
        )

    private fun terminalLayout(): WorkbenchTerminalLayout {
        val (terminalColumns, terminalRows) = terminalDimensions(menu.screenSnapshot)
        val dockBounds =
            layout()
                .terminalDockBounds
                ?.let { TerminalRect(it.x, it.y, it.width, it.height) }
                ?: TerminalRect(leftPos + 8, topPos + imageHeight - 188, imageWidth - 16, 180)
        val panelBounds = TerminalRect(dockBounds.x, dockBounds.y, dockBounds.width, dockBounds.height)
        val statusBounds = TerminalRect(panelBounds.x, panelBounds.y + panelBounds.height - 20, panelBounds.width, 20)
        val terminalSurfaceBounds = TerminalRect(panelBounds.x, panelBounds.y, panelBounds.width, statusBounds.y - panelBounds.y)
        val terminalBounds =
            TerminalRect(
                terminalSurfaceBounds.x,
                terminalSurfaceBounds.y,
                min(terminalColumns * TerminalFontConstants.FONT_WIDTH, terminalSurfaceBounds.width),
                min(terminalRows * TerminalFontConstants.FONT_HEIGHT, terminalSurfaceBounds.height),
            )
        return WorkbenchTerminalLayout(panelBounds, terminalSurfaceBounds, terminalBounds, statusBounds)
    }

    private fun syncFullscreenWindowSize() {
        if (imageWidth != width || imageHeight != height || leftPos != 0 || topPos != 0) {
            imageWidth = width
            imageHeight = height
            leftPos = 0
            topPos = 0
        }
    }

    private fun syncSlotPositions() {
        val layout = layout()
        val targetSlot = menu.slots.getOrNull(0) as? WorkbenchPositionableSlot ?: return
        targetSlot.relocate(layout.targetSlotBounds.x, layout.targetSlotBounds.y)

        val inventoryBounds = layout.inventoryBounds
        val originX = inventoryBounds.x + (inventoryBounds.width - INVENTORY_GRID_WIDTH) / 2
        val mainOriginY = inventoryBounds.y
        val hotbarY = inventoryBounds.y + HOTBAR_OFFSET_Y

        for (row in 0 until 3) {
            for (column in 0 until 9) {
                val slot = menu.slots.getOrNull(1 + row * 9 + column) as? WorkbenchPositionableSlot ?: return
                slot.relocate(originX + column * SLOT_SPACING, mainOriginY + row * SLOT_SPACING)
            }
        }

        for (column in 0 until 9) {
            val slot = menu.slots.getOrNull(28 + column) as? WorkbenchPositionableSlot ?: return
            slot.relocate(originX + column * SLOT_SPACING, hotbarY)
        }
    }

    private fun terminalAcceptsInput(terminalState: WorkbenchTerminalViewState): Boolean =
        WorkbenchTerminalInteractionPolicy.canAcceptInput(
            if (store.state.terminalVisible) WorkbenchMode.TERMINAL else WorkbenchMode.EDITOR,
            terminalState,
            terminalInput.focused,
        )

    private fun terminalDimensions(snapshot: ScreenBufferSnapshot?): Pair<Int, Int> =
        if (snapshot == null) {
            16 to 8
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

    companion object {
        private const val SLOT_SPACING = 18
        private const val INVENTORY_GRID_WIDTH = SLOT_SPACING * 9
        private const val HOTBAR_OFFSET_Y = SLOT_SPACING * 3 + 4
    }
}
