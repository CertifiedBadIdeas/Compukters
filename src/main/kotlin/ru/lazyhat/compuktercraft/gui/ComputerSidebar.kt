// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.gui

import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.network.chat.Component
import ru.lazyhat.compuktercraft.menu.AbstractComputerMenu
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
                Component.translatable("gui.compuktercraft.tooltip.turn_on"),
                null as Component?,
            )
        val turnOff: DynamicImageButton.HintedMessage =
            DynamicImageButton.HintedMessage(
                Component.translatable("gui.compuktercraft.tooltip.turn_off"),
                Component.translatable("gui.compuktercraft.tooltip.turn_off.key"),
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
                    Component.translatable("gui.compuktercraft.tooltip.terminate"),
                    Component.translatable("gui.compuktercraft.tooltip.terminate.key"),
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
