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
package ru.lazyhat.compukterkraft.common.serial.screen

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.serial.menu.SerialTerminalMenu
import ru.lazyhat.compukterkraft.common.serial.network.server.SerialConsoleInputServerMessage
import ru.lazyhat.compukterkraft.core.input.KeyCodes

class SerialTerminalScreen(
    menu: SerialTerminalMenu,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<SerialTerminalMenu>(menu, inventory, title) {
    init {
        imageWidth = WIDTH
        imageHeight = HEIGHT
    }

    override fun init() {
        super.init()
        leftPos = (width - imageWidth) / 2
        topPos = (height - imageHeight) / 2
    }

    override fun render(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun renderLabels(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
    }

    override fun renderBg(
        guiGraphics: GuiGraphics,
        partialTick: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BACKGROUND)
        guiGraphics.fill(leftPos + 6, topPos + 18, leftPos + imageWidth - 6, topPos + imageHeight - 24, PANEL)
        guiGraphics.fill(leftPos + 6, topPos + imageHeight - 20, leftPos + imageWidth - 6, topPos + imageHeight - 6, INPUT)
        guiGraphics.drawString(font, title, leftPos + 8, topPos + 7, TITLE, false)
        drawConnectionStatus(guiGraphics)

        val lines = visibleOutputLines()
        var y = topPos + 22
        for (line in lines) {
            guiGraphics.drawString(font, truncateToWidth(line, imageWidth - 18), leftPos + 10, y, TEXT, false)
            y += LINE_HEIGHT
        }
        val input = "> ${menu.serialBuffer.inputLine}"
        guiGraphics.drawString(font, truncateToWidth(input, imageWidth - 18), leftPos + 10, topPos + imageHeight - 17, PROMPT, false)
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean =
        when {
            keyCode == KeyCodes.KEY_ENTER || keyCode == KeyCodes.KEY_KP_ENTER -> {
                ClientNetworking.sendToServer(
                    SerialConsoleInputServerMessage(menu.containerId, menu.serialBuffer.submitLine()),
                )
                true
            }
            keyCode == KeyCodes.KEY_BACKSPACE -> {
                menu.serialBuffer.backspace()
                true
            }
            isInventoryKey(keyCode, scanCode) -> true
            else -> super.keyPressed(keyCode, scanCode, modifiers)
        }

    override fun charTyped(
        codePoint: Char,
        modifiers: Int,
    ): Boolean {
        menu.serialBuffer.type(codePoint)
        return true
    }

    private fun visibleOutputLines(): List<String> {
        val all = buildList {
            addAll(menu.serialBuffer.historyLines)
            if (menu.serialBuffer.pendingOutputLine.isNotEmpty()) {
                add(menu.serialBuffer.pendingOutputLine)
            }
        }
        val maxLines = ((imageHeight - 48) / LINE_HEIGHT).coerceAtLeast(1)
        return all.takeLast(maxLines)
    }

    private fun drawConnectionStatus(guiGraphics: GuiGraphics) {
        val statusKey =
            if (menu.isComputerOn) {
                "gui.compukterkraft.serial_terminal.connected_on"
            } else {
                "gui.compukterkraft.serial_terminal.connected_off"
            }
        val status = Component.translatable(statusKey)
        val color = if (menu.isComputerOn) STATUS_ON else STATUS_OFF
        guiGraphics.drawString(
            font,
            status,
            leftPos + imageWidth - 8 - font.width(status),
            topPos + 7,
            color,
            false,
        )
    }

    private fun isInventoryKey(
        keyCode: Int,
        scanCode: Int,
    ): Boolean = minecraft?.options?.keyInventory?.matches(keyCode, scanCode) == true

    private fun truncateToWidth(
        text: String,
        maxWidth: Int,
    ): String {
        var result = text
        while (result.isNotEmpty() && font.width(result) > maxWidth) {
            result = result.drop(1)
        }
        return result
    }

    companion object {
        private const val WIDTH = 320
        private const val HEIGHT = 210
        private const val LINE_HEIGHT = 10
        private const val BACKGROUND = 0xFF151922.toInt()
        private const val PANEL = 0xFF0B0F14.toInt()
        private const val INPUT = 0xFF202735.toInt()
        private const val TITLE = 0xFFE6ECF5.toInt()
        private const val TEXT = 0xFFB7C5D8.toInt()
        private const val PROMPT = 0xFF7CFFB2.toInt()
        private const val STATUS_ON = 0xFF7CFFB2.toInt()
        private const val STATUS_OFF = 0xFFFFC857.toInt()
    }
}
