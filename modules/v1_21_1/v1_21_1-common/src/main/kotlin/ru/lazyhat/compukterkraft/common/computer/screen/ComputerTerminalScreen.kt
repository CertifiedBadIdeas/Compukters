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
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchMode
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
        imageHeight = WorkbenchTerminalMetrics.imageHeight(terminalColumns, terminalRows)
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
        )
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        renderBackground(graphics, mouseX, mouseY, partialTicks)
        super.render(graphics, mouseX, mouseY, partialTicks)
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
}