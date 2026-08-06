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

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.client.retained.ClientRetainedDisplays
import ru.lazyhat.compukterkraft.common.computer.client.retained.RetainedDisplayMenuRenderer
import ru.lazyhat.compukterkraft.common.computer.client.retained.RetainedDisplayObserverHandle
import ru.lazyhat.compukterkraft.common.computer.input.ClientInputHandler
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.platform.MinecraftInputProvider
import ru.lazyhat.compukterkraft.common.ui.program.DslContainerScreen
import ru.lazyhat.compukterkraft.common.utils.computerDataTagCopy
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.core.device.display.retained.render.RetainedDisplayGeometryCompiler
import ru.lazyhat.compukterkraft.core.gui.TerminalRect
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalInputController
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalLayout
import ru.lazyhat.kraftui.foundation.CanvasScope
import ru.lazyhat.kraftui.foundation.Color

abstract class ComputerDisplayScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : DslContainerScreen<T>(container, player, title) {
    protected val inputHandler = ClientInputHandler(container)
    protected val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)

    private val retainedRenderer = RetainedDisplayMenuRenderer()
    private var retainedObserver: RetainedDisplayObserverHandle? = null

    override fun init() {
        super.init()
        if (retainedObserver == null) {
            retainedObserver = ClientRetainedDisplays.attachMenu(computerId(), menu.containerId)
        }
        focusFirstNodeIfUnfocused()
    }

    override fun removed() {
        retainedObserver?.close()
        retainedObserver = null
        super.removed()
    }

    override fun renderBg(
        guiGraphics: GuiGraphics,
        partialTick: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY)
        drawRetainedDisplay(guiGraphics)
    }

    override fun containerTick() {
        super.containerTick()
        focusFirstNodeIfUnfocused()
    }

    override fun mouseClicked(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        val handled = super.mouseClicked(x, y, button)
        focusFirstNodeIfUnfocused()
        return handled
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (menu.isComputerOn && terminalInput.keyPressed(keyCode, scanCode, modifiers)) return true
        return isInventoryKey(keyCode, scanCode) || super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun keyReleased(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (menu.isComputerOn && terminalInput.keyReleased(keyCode, scanCode)) return true
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun charTyped(
        codePoint: Char,
        modifiers: Int,
    ): Boolean {
        if (menu.isComputerOn && terminalInput.charTyped(codePoint)) return true
        return super.charTyped(codePoint, modifiers)
    }

    protected abstract fun currentLayout(): WorkbenchTerminalLayout

    protected fun CanvasScope.drawDisplayPlaceholder(
        targetWidth: Int,
        targetHeight: Int,
    ) {
        if (!hasRetainedDisplayPresentation()) {
            fillRect(0, 0, targetWidth, targetHeight, DISPLAY_PLACEHOLDER)
        }
    }

    protected fun hasRetainedDisplayPresentation(): Boolean = retainedObserver?.presentation() != null

    protected fun currentDisplayWidth(): Int = RetainedDisplayGeometryCompiler.LOGICAL_WIDTH

    protected fun currentDisplayHeight(): Int = RetainedDisplayGeometryCompiler.LOGICAL_HEIGHT

    protected fun displayResolutionText(
        width: Int,
        height: Int,
    ): String = "$width x $height"

    protected fun currentDisplayBounds(layout: WorkbenchTerminalLayout): TerminalRect {
        val surface = layout.terminalSurfaceBounds
        val width = currentDisplayWidth()
        val height = currentDisplayHeight()
        return TerminalRect(
            x = surface.x + (surface.width - width) / 2,
            y = surface.y + (surface.height - height) / 2,
            width = width,
            height = height,
        )
    }

    private fun drawRetainedDisplay(guiGraphics: GuiGraphics) {
        if (!menu.isComputerOn) return
        val presentation = retainedObserver?.presentation() ?: return
        retainedRenderer.draw(guiGraphics, currentDisplayBounds(currentLayout()), presentation)
    }

    private fun computerId(): Int =
        requireNotNull(menu.displayStack.computerDataTagCopy()?.computerID) {
            "Computer display menu does not contain a computer ID"
        }.also {
            require(it > 0) { "Computer display menu contains invalid computer ID: $it" }
        }

    private fun isInventoryKey(
        keyCode: Int,
        scanCode: Int,
    ): Boolean =
        Minecraft
            .getInstance()
            .options
            .keyInventory
            .matches(keyCode, scanCode)

    private companion object {
        private val DISPLAY_PLACEHOLDER = Color.hex(0xFF05070AU)
    }
}
