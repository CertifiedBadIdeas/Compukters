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
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.serial.menu.SerialTerminalMenu
import ru.lazyhat.compukterkraft.common.serial.network.server.SerialConsoleInputServerMessage
import ru.lazyhat.compukterkraft.common.ui.program.DslContainerScreen
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.UiAlignment
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.fillMaxSize
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.fillMaxWidth
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.height
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.padding
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.weight
import ru.lazyhat.compukterkraft.core.ui.foundation.ui

class SerialTerminalScreen(
    menu: SerialTerminalMenu,
    inventory: Inventory,
    title: Component,
) : DslContainerScreen<SerialTerminalMenu>(menu, inventory, title) {
    init {
        imageWidth = WIDTH
        imageHeight = HEIGHT
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

    override fun init() {
        super.init()
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

    override fun content(): UiElement =
        ui(Modifier.fillMaxSize().background(BACKGROUND).padding(SCREEN_PADDING)) {
            column(Modifier.fillMaxSize()) {
                row(
                    modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT),
                    gap = 4,
                    verticalAlignment = UiAlignment.Center,
                ) {
                    text(
                        modifier = Modifier.weight(1f),
                        color = TITLE,
                    ) {
                        truncateToWidth(title.string, TITLE_TEXT_WIDTH)
                    }
                    text(
                        color = STATUS,
                    ) {
                        truncateToWidth(serialStatusText(), STATUS_TEXT_WIDTH)
                    }
                }

                box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(PANEL)
                        .padding(horizontal = 4, vertical = 3),
                ) {
                    column(Modifier.fillMaxSize()) {
                        for (row in 0 until VISIBLE_OUTPUT_LINES) {
                            text(
                                modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT),
                                color = TEXT,
                            ) {
                                visibleOutputLine(row)
                            }
                        }
                    }
                }

                box(
                    Modifier
                        .fillMaxWidth()
                        .height(INPUT_HEIGHT)
                        .background(INPUT)
                        .padding(horizontal = 4, vertical = 3),
                ) {
                    text(
                        modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT),
                        color = PROMPT,
                    ) {
                        truncateToWidth("> ${menu.serialBuffer.inputLine}", INPUT_TEXT_WIDTH)
                    }
                }
            }
            keySurface(
                modifier = Modifier.fillMaxSize(),
                id = "serial-terminal",
                onKeyPressed = ::handleSerialKey,
                onCharTyped = ::handleSerialChar,
            )
        }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean = isInventoryKey(keyCode, scanCode) || super.keyPressed(keyCode, scanCode, modifiers)

    private fun visibleOutputLines(): List<String> {
        val all = buildList {
            addAll(menu.serialBuffer.historyLines)
            if (menu.serialBuffer.pendingOutputLine.isNotEmpty()) {
                add(menu.serialBuffer.pendingOutputLine)
            }
        }
        return all.takeLast(VISIBLE_OUTPUT_LINES.coerceAtLeast(1))
    }

    private fun visibleOutputLine(row: Int): String = truncateToWidth(visibleOutputLines().getOrNull(row) ?: "", OUTPUT_TEXT_WIDTH)

    private fun serialStatusText(): String =
        "${Component.translatable("gui.compukterkraft.serial_terminal.linked").string}  RX ${menu.serialBuffer.rxBytes}  TX ${menu.serialBuffer.txBytes}"

    private fun handleSerialKey(keyCode: Int): Boolean =
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
            else -> false
        }

    private fun handleSerialChar(codePoint: Char): Boolean {
        menu.serialBuffer.type(codePoint)
        return true
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
        private const val SCREEN_PADDING = 6
        private const val HEADER_HEIGHT = 16
        private const val INPUT_HEIGHT = 16
        private const val PANEL_PADDING_HORIZONTAL = 4
        private const val INPUT_PADDING_HORIZONTAL = 4
        private const val LINE_HEIGHT = 10
        private const val CONTENT_WIDTH = WIDTH - SCREEN_PADDING * 2
        private const val OUTPUT_TEXT_WIDTH = CONTENT_WIDTH - PANEL_PADDING_HORIZONTAL * 2
        private const val INPUT_TEXT_WIDTH = CONTENT_WIDTH - INPUT_PADDING_HORIZONTAL * 2
        private const val STATUS_TEXT_WIDTH = 138
        private const val TITLE_TEXT_WIDTH = CONTENT_WIDTH - STATUS_TEXT_WIDTH - 4
        private const val VISIBLE_OUTPUT_LINES = (HEIGHT - SCREEN_PADDING * 2 - HEADER_HEIGHT - INPUT_HEIGHT) / LINE_HEIGHT
        private val BACKGROUND = Color.hex(0xFF151922u)
        private val PANEL = Color.hex(0xFF0B0F14u)
        private val INPUT = Color.hex(0xFF202735u)
        private val TITLE = Color.hex(0xFFE6ECF5u)
        private val TEXT = Color.hex(0xFFB7C5D8u)
        private val PROMPT = Color.hex(0xFF7CFFB2u)
        private val STATUS = Color.hex(0xFF7CFFB2u)
    }
}
