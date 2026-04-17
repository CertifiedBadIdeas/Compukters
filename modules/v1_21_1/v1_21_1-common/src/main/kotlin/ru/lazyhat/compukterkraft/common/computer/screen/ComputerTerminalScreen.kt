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
package ru.lazyhat.compukterkraft.common.computer.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.input.ClientInputHandler
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.platform.MinecraftInputProvider
import ru.lazyhat.compukterkraft.common.ui.render.WorkbenchTerminalRenderer
import ru.lazyhat.compukterkraft.core.computer.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.computer.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchMode
import ru.lazyhat.compukterkraft.core.gui.TerminalRect
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalInputController
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalLayout
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalInteractionPolicy
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewState
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

class ComputerTerminalScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : ComputerScreen<T>(container, player, title) {
    private val inputHandler = ClientInputHandler(container)
    private val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)

    init {
        val (terminalColumns, terminalRows) = terminalDimensions(container.clientSide.screenSnapshot)
        imageWidth = WorkbenchTerminalMetrics.imageWidth(terminalColumns, terminalRows)
        imageHeight = WorkbenchTerminalMetrics.imageHeight(terminalColumns, terminalRows, contentTopInset = COMPUTER_CONTENT_TOP)
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
        val snapshot = menu.clientSide.screenSnapshot
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, snapshot)
        val focused =
            WorkbenchTerminalInteractionPolicy.canAcceptInput(
                WorkbenchMode.TERMINAL,
                terminalState,
                terminalInput.focused,
            )
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
            statusRightInset = STATUS_TEXT_RIGHT_INSET,
        )

        renderControlButtons(graphics, mouseX, mouseY)
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        renderBackground(graphics, mouseX, mouseY, partialTicks)
        super.render(graphics, mouseX, mouseY, partialTicks)
        renderControlTooltip(graphics, mouseX, mouseY)
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
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, terminalState, terminalInput.focused)) {
            if (terminalInput.keyPressed(key, scancode, modifiers)) {
                return true
            }
        }
        return super.keyPressed(key, scancode, modifiers)
    }

    override fun keyReleased(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, terminalState, terminalInput.focused)) {
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
        if (WorkbenchTerminalInteractionPolicy.canAcceptInput(WorkbenchMode.TERMINAL, terminalState, terminalInput.focused)) {
            return terminalInput.charTyped(ch)
        }
        return super.charTyped(ch, modifiers)
    }

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        if (button == 0) {
            controlButtonAt(mouseX.toInt(), mouseY.toInt())?.let { controlButton ->
                terminalInput.focused = false
                inputHandler.accept(ControlInputEvent(controlButton.action))
                return true
            }
        }

        val terminalState = WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)
        if (terminalState is WorkbenchTerminalViewState.Active) {
            terminalInput.focused = terminalInput.mouseClicked(terminalLayout().terminalBounds, mouseX, mouseY)
        } else {
            terminalInput.focused = false
        }
        return terminalInput.focused || super.mouseClicked(mouseX, mouseY, button)
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
            contentTopInset = COMPUTER_CONTENT_TOP,
        )
    }

    private fun syncTerminalWindowSize(terminalState: WorkbenchTerminalViewState) {
        val (terminalColumns, terminalRows) =
            when (terminalState) {
                is WorkbenchTerminalViewState.Active -> terminalDimensions(terminalState.snapshot)
                WorkbenchTerminalViewState.PoweredOff, WorkbenchTerminalViewState.Connecting -> 0 to 0
            }

        val nextWidth = WorkbenchTerminalMetrics.imageWidth(terminalColumns, terminalRows)
        val nextHeight = WorkbenchTerminalMetrics.imageHeight(terminalColumns, terminalRows, contentTopInset = COMPUTER_CONTENT_TOP)
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

    private fun renderControlButtons(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        controlButtons().forEach { button ->
            val hovered = button.bounds.contains(mouseX, mouseY)
            val background = if (hovered) BUTTON_BACKGROUND_HOVER else BUTTON_BACKGROUND
            graphics.fill(button.bounds.x, button.bounds.y, button.bounds.x + button.bounds.width, button.bounds.y + button.bounds.height, background)
            graphics.fill(button.bounds.x, button.bounds.y, button.bounds.x + button.bounds.width, button.bounds.y + 1, button.accent)
            graphics.fill(button.bounds.x, button.bounds.y + button.bounds.height - 1, button.bounds.x + button.bounds.width, button.bounds.y + button.bounds.height, BUTTON_BORDER)
            graphics.fill(button.bounds.x, button.bounds.y, button.bounds.x + 1, button.bounds.y + button.bounds.height, BUTTON_BORDER)
            graphics.fill(button.bounds.x + button.bounds.width - 1, button.bounds.y, button.bounds.x + button.bounds.width, button.bounds.y + button.bounds.height, BUTTON_BORDER)
            when (button.kind) {
                ControlButtonKind.POWER -> renderPowerIcon(graphics, button.bounds.x, button.bounds.y, button.iconColor)
                ControlButtonKind.REBOOT -> renderRebootIcon(graphics, button.bounds.x, button.bounds.y, button.iconColor)
            }
        }
    }

    private fun renderControlTooltip(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        val tooltipKey = controlButtonAt(mouseX, mouseY)?.tooltipKey ?: return
        graphics.renderTooltip(font, Component.translatable(tooltipKey), mouseX, mouseY)
    }

    private fun controlButtons(): List<ControlButton> {
        val statusBounds = terminalLayout().statusBounds
        val rebootBounds = statusButtonBounds(statusBounds, slotFromRight = 0)
        val powerBounds = statusButtonBounds(statusBounds, slotFromRight = 1)
        val powerAction = if (menu.isComputerOn) ComputerControlAction.SHUTDOWN else ComputerControlAction.TURN_ON
        val powerTooltipKey = if (menu.isComputerOn) "gui.compukterkraft.control.shutdown" else "gui.compukterkraft.control.turn_on"

        return listOf(
            ControlButton(
                kind = ControlButtonKind.POWER,
                action = powerAction,
                tooltipKey = powerTooltipKey,
                bounds = powerBounds,
                accent = POWER_ACCENT,
                iconColor = BUTTON_ICON,
            ),
            ControlButton(
                kind = ControlButtonKind.REBOOT,
                action = ComputerControlAction.REBOOT,
                tooltipKey = "gui.compukterkraft.control.reboot",
                bounds = rebootBounds,
                accent = REBOOT_ACCENT,
                iconColor = BUTTON_ICON,
            ),
        )
    }

    private fun controlButtonAt(
        mouseX: Int,
        mouseY: Int,
    ): ControlButton? = controlButtons().firstOrNull { it.bounds.contains(mouseX, mouseY) }

    private fun statusButtonBounds(
        statusBounds: TerminalRect,
        slotFromRight: Int,
    ): TerminalRect {
        val x = statusBounds.x + statusBounds.width - STATUS_BUTTON_MARGIN_END - STATUS_BUTTON_SIZE * (slotFromRight + 1) - STATUS_BUTTON_GAP * slotFromRight
        val y = statusBounds.y + (statusBounds.height - STATUS_BUTTON_SIZE) / 2
        return TerminalRect(x, y, STATUS_BUTTON_SIZE, STATUS_BUTTON_SIZE)
    }

    private fun renderPowerIcon(
        graphics: GuiGraphics,
        buttonX: Int,
        buttonY: Int,
        color: Int,
    ) {
        val originX = buttonX + 4
        val originY = buttonY + 3
        graphics.fill(originX + 4, originY, originX + 6, originY + 5, color)
        graphics.fill(originX + 2, originY + 4, originX + 4, originY + 9, color)
        graphics.fill(originX + 6, originY + 4, originX + 8, originY + 9, color)
        graphics.fill(originX + 3, originY + 8, originX + 7, originY + 10, color)
    }

    private fun renderRebootIcon(
        graphics: GuiGraphics,
        buttonX: Int,
        buttonY: Int,
        color: Int,
    ) {
        val originX = buttonX + 3
        val originY = buttonY + 3
        graphics.fill(originX + 2, originY, originX + 8, originY + 2, color)
        graphics.fill(originX + 1, originY + 2, originX + 3, originY + 7, color)
        graphics.fill(originX + 3, originY + 6, originX + 8, originY + 8, color)
        graphics.fill(originX + 7, originY + 1, originX + 9, originY + 6, color)
        graphics.fill(originX + 7, originY, originX + 11, originY + 2, color)
        graphics.fill(originX + 8, originY + 2, originX + 11, originY + 5, color)
    }

    private data class ControlButton(
        val kind: ControlButtonKind,
        val action: ComputerControlAction,
        val tooltipKey: String,
        val bounds: TerminalRect,
        val accent: Int,
        val iconColor: Int,
    )

    private enum class ControlButtonKind {
        POWER,
        REBOOT,
    }

    private companion object {
        private const val COMPUTER_CONTENT_TOP = 8
        private const val STATUS_BUTTON_SIZE = 14
        private const val STATUS_BUTTON_GAP = 6
        private const val STATUS_BUTTON_MARGIN_END = 10
        private const val STATUS_TEXT_RIGHT_INSET = 52
        private const val BUTTON_BACKGROUND = 0xFF1B202A.toInt()
        private const val BUTTON_BACKGROUND_HOVER = 0xFF222938.toInt()
        private const val BUTTON_BORDER = 0xFF2C3444.toInt()
        private const val BUTTON_ICON = 0xFFE6ECF5.toInt()
        private const val POWER_ACCENT = 0xFF4FA56C.toInt()
        private const val REBOOT_ACCENT = 0xFFC9894F.toInt()
    }
}