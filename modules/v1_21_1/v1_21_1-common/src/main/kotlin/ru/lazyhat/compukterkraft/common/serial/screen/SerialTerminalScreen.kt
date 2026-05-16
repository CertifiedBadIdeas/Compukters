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
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.focusable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.offset
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import ru.lazyhat.compukterkraft.core.ui.foundation.value

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
        ui(Modifier.size(imageWidth, imageHeight).background(BACKGROUND)) {
            box(
                Modifier
                    .offset(6, 18)
                    .size(imageWidth - 12, imageHeight - 42)
                    .background(PANEL),
            )
            box(
                Modifier
                    .offset(6, imageHeight - 20)
                    .size(imageWidth - 12, 14)
                    .background(INPUT),
            )
            text(
                modifier = Modifier.offset(8, 7),
                color = TITLE,
                text = value { title.string },
            )
            text(
                modifier = Modifier.offset(STATUS_X, 7),
                color = STATUS,
                text = value { serialStatusText() },
            )

            for (row in 0 until VISIBLE_OUTPUT_LINES) {
                text(
                    modifier = Modifier.offset(10, 22 + row * LINE_HEIGHT),
                    color = TEXT,
                    text = value { visibleOutputLine(row) },
                )
            }

            text(
                modifier = Modifier.offset(10, imageHeight - 17),
                color = PROMPT,
                text = value { truncateToWidth("> ${menu.serialBuffer.inputLine}", imageWidth - 18) },
            )
            canvas(
                modifier =
                    Modifier
                        .offset(6, 18)
                        .size(imageWidth - 12, imageHeight - 24)
                        .focusable(
                            id = "serial-terminal",
                            onKeyPressed = ::handleSerialKey,
                            onCharTyped = ::handleSerialChar,
                        ),
            ) {
                // Focus target only. Visible chrome and text are authored as DSL elements.
            }
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
        val maxLines = ((imageHeight - 48) / LINE_HEIGHT).coerceAtLeast(1)
        return all.takeLast(maxLines)
    }

    private fun visibleOutputLine(row: Int): String = truncateToWidth(visibleOutputLines().getOrNull(row) ?: "", imageWidth - 18)

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
        private const val LINE_HEIGHT = 10
        private const val VISIBLE_OUTPUT_LINES = (HEIGHT - 48) / LINE_HEIGHT
        private const val STATUS_X = 178
        private val BACKGROUND = Color.hex(0xFF151922u)
        private val PANEL = Color.hex(0xFF0B0F14u)
        private val INPUT = Color.hex(0xFF202735u)
        private val TITLE = Color.hex(0xFFE6ECF5u)
        private val TEXT = Color.hex(0xFFB7C5D8u)
        private val PROMPT = Color.hex(0xFF7CFFB2u)
        private val STATUS = Color.hex(0xFF7CFFB2u)
    }
}
