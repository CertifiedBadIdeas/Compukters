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
package ru.lazyhat.compukterkraft.gui

import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import ru.lazyhat.compukterkraft.menu.AbstractComputerMenu
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/**
 * Registers buttons to interact with a computer.
 */
object ComputerSidebar {
    private const val ICON_WIDTH = 12
    private const val ICON_HEIGHT = 12
    private const val ICON_MARGIN = 2

    private const val CORNERS_BORDER = 3
    private val FULL_BORDER = CORNERS_BORDER + ICON_MARGIN

    private const val BUTTONS = 2
    private val HEIGHT = (ICON_HEIGHT + ICON_MARGIN * 2) * BUTTONS + CORNERS_BORDER * 2

    private const val TEX_HEIGHT = 14

    fun addButtons(
        isOn: BooleanSupplier,
        input: InputHandler,
        add: Consumer<AbstractWidget>,
        x: Int,
        y: Int,
    ) {
        var x = x
        var y = y
        x += CORNERS_BORDER + 1
        y += CORNERS_BORDER + ICON_MARGIN

        val turnOn: DynamicImageButton.HintedMessage =
            DynamicImageButton.HintedMessage(
                Component.translatable("gui.compukterkraft.tooltip.turn_on"),
                null as Component?,
            )
        val turnOff: DynamicImageButton.HintedMessage =
            DynamicImageButton.HintedMessage(
                Component.translatable("gui.compukterkraft.tooltip.turn_off"),
                Component.translatable("gui.compukterkraft.tooltip.turn_off.key"),
            )
        add.accept(
            DynamicImageButton(
                x,
                y,
                ICON_WIDTH,
                ICON_HEIGHT,
                { h -> if (isOn.getAsBoolean()) GuiSprites.TURNED_ON.get(h) else GuiSprites.TURNED_OFF.get(h) },
                { b -> toggleComputer(isOn, input) },
                { if (isOn.getAsBoolean()) turnOff else turnOn },
            ),
        )

        y += ICON_HEIGHT + ICON_MARGIN * 2

        add.accept(
            DynamicImageButton(
                x,
                y,
                ICON_WIDTH,
                ICON_HEIGHT,
                GuiSprites.TERMINATE::get,
                { b -> input.terminate() },
                DynamicImageButton.HintedMessage(
                    Component.translatable("gui.compukterkraft.tooltip.terminate"),
                    Component.translatable("gui.compukterkraft.tooltip.terminate.key"),
                ),
            ),
        )
    }

    fun renderBackground(
        renderer: SpriteRenderer,
        textures: GuiSprites.ComputerTextures,
        x: Int,
        y: Int,
    ) {
        val texture = textures.sidebar ?: throw NullPointerException(textures.toString() + " has no sidebar texture")
        val sprite = GuiSprites.get(texture)

        renderer.blitVerticalSliced(sprite, x, y, AbstractComputerMenu.SIDEBAR_WIDTH, HEIGHT, FULL_BORDER, FULL_BORDER, TEX_HEIGHT)
    }

    private fun toggleComputer(
        isOn: BooleanSupplier,
        input: InputHandler,
    ) {
        if (isOn.asBoolean) {
            input.shutdown()
        } else {
            input.turnOn()
        }
    }
}
