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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerControlMenu
import ru.lazyhat.compukterkraft.common.computer.network.server.ComputerActionServerMessage
import ru.lazyhat.compukterkraft.common.network.ClientNetworking

class ComputerControlScreen(
    menu: ComputerControlMenu,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<ComputerControlMenu>(menu, inventory, title) {
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
        guiGraphics.drawString(font, title, leftPos + 10, topPos + 9, TITLE, false)
        val statusKey =
            if (menu.isComputerOn) {
                "gui.compukterkraft.computer_control.status_on"
            } else {
                "gui.compukterkraft.computer_control.status_off"
            }
        guiGraphics.drawString(
            font,
            Component.translatable(statusKey),
            leftPos + 10,
            topPos + 30,
            TEXT,
            false,
        )
        val primary =
            if (menu.isComputerOn) {
                ControlButton(
                    x = leftPos + 10,
                    y = topPos + 54,
                    label = Component.translatable("gui.compukterkraft.control.shutdown"),
                    action = ComputerActionServerMessage.Action.SHUTDOWN,
                )
            } else {
                ControlButton(
                    x = leftPos + 10,
                    y = topPos + 54,
                    label = Component.translatable("gui.compukterkraft.control.turn_on"),
                    action = ComputerActionServerMessage.Action.TURN_ON,
                )
            }
        drawButton(guiGraphics, primary)
        if (menu.isComputerOn) {
            drawButton(
                guiGraphics,
                ControlButton(
                    x = leftPos + 110,
                    y = topPos + 54,
                    label = Component.translatable("gui.compukterkraft.control.reboot"),
                    action = ComputerActionServerMessage.Action.REBOOT,
                ),
            )
        }
    }

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        if (button == 0) {
            buttonAt(mouseX.toInt(), mouseY.toInt())?.let { control ->
                ClientNetworking.sendToServer(ComputerActionServerMessage(menu, control.action))
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun buttonAt(
        mouseX: Int,
        mouseY: Int,
    ): ControlButton? {
        val buttons =
            buildList {
                add(
                    if (menu.isComputerOn) {
                        ControlButton(
                            leftPos + 10,
                            topPos + 54,
                            Component.translatable("gui.compukterkraft.control.shutdown"),
                            ComputerActionServerMessage.Action.SHUTDOWN,
                        )
                    } else {
                        ControlButton(
                            leftPos + 10,
                            topPos + 54,
                            Component.translatable("gui.compukterkraft.control.turn_on"),
                            ComputerActionServerMessage.Action.TURN_ON,
                        )
                    },
                )
                if (menu.isComputerOn) {
                    add(
                        ControlButton(
                            leftPos + 110,
                            topPos + 54,
                            Component.translatable("gui.compukterkraft.control.reboot"),
                            ComputerActionServerMessage.Action.REBOOT,
                        ),
                    )
                }
            }
        return buttons.firstOrNull { mouseX >= it.x && mouseX < it.x + BUTTON_WIDTH && mouseY >= it.y && mouseY < it.y + BUTTON_HEIGHT }
    }

    private fun drawButton(
        guiGraphics: GuiGraphics,
        button: ControlButton,
    ) {
        guiGraphics.fill(button.x, button.y, button.x + BUTTON_WIDTH, button.y + BUTTON_HEIGHT, BUTTON)
        guiGraphics.drawString(font, button.label, button.x + 8, button.y + 6, TITLE, false)
    }

    private data class ControlButton(
        val x: Int,
        val y: Int,
        val label: Component,
        val action: ComputerActionServerMessage.Action,
    )

    companion object {
        private const val WIDTH = 220
        private const val HEIGHT = 98
        private const val BUTTON_WIDTH = 92
        private const val BUTTON_HEIGHT = 22
        private const val BACKGROUND = 0xFF151922.toInt()
        private const val BUTTON = 0xFF2A3446.toInt()
        private const val TITLE = 0xFFE6ECF5.toInt()
        private const val TEXT = 0xFFB7C5D8.toInt()
    }
}
