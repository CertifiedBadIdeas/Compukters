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

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineLabel
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import ru.lazyhat.compukterkraft.MOD_ID
import kotlin.math.max

/**
 * A screen which displays a series of buttons (such as a yes/no prompt).
 *
 *
 * When closed, it returns to the previous screen.
 */
class OptionScreen private constructor(
    title: Component,
    private val message: Component,
    private val buttons: MutableList<AbstractWidget>,
    private val exit: Runnable,
    val originalScreen: Screen?,
) : Screen(title) {
    private var x = 0
    private var y = 0
    private var innerWidth = 0
    private var innerHeight = 0

    private var messageRenderer: MultiLineLabel? = null

    public override fun init() {
        super.init()

        val buttonWidth: Int = BUTTON_WIDTH * buttons.size + PADDING * (buttons.size - 1)
        this.innerWidth = max(256, buttonWidth + PADDING * 2)
        val innerWidth = this.innerWidth

        messageRenderer = MultiLineLabel.create(font, message, innerWidth - PADDING * 2)

        val textHeight: Int = messageRenderer!!.lineCount * FONT_HEIGHT + PADDING * 2
        innerHeight = textHeight + (if (buttons.isEmpty()) 0 else buttons[0].getHeight()) + PADDING

        x = (width - innerWidth) / 2
        y = (height - innerHeight) / 2

        var x = (width - buttonWidth) / 2
        for (button in buttons) {
            button.setPosition(x, y + textHeight)
            addRenderableWidget<AbstractWidget?>(button)

            x += BUTTON_WIDTH + PADDING
        }
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        renderBackground(graphics)

        // Render the actual texture.
        graphics.blit(BACKGROUND, x, y, 0, 0, innerWidth, PADDING)
        graphics.blit(
            BACKGROUND,
            x,
            y + PADDING,
            0f,
            PADDING.toFloat(),
            innerWidth,
            innerHeight - PADDING * 2,
            innerWidth,
            PADDING,
        )
        graphics.blit(BACKGROUND, x, y + innerHeight - PADDING, 0, 256 - PADDING, innerWidth, PADDING)

        checkNotNull(messageRenderer).renderLeftAlignedNoShadow(graphics, x + PADDING, y + PADDING, FONT_HEIGHT, 0x404040)
        super.render(graphics, mouseX, mouseY, partialTicks)
    }

    override fun onClose() {
        exit.run()
    }

    companion object {
        private val BACKGROUND = ResourceLocation.fromNamespaceAndPath(MOD_ID, "textures/gui/blank_screen.png")

        const val BUTTON_WIDTH: Int = 100
        const val BUTTON_HEIGHT: Int = 20

        private const val PADDING = 16
        private const val FONT_HEIGHT = 9

        fun show(
            minecraft: Minecraft,
            title: Component,
            message: Component,
            buttons: MutableList<AbstractWidget>,
            exit: Runnable,
        ) {
            minecraft.setScreen(OptionScreen(title, message, buttons, exit, unwrap(minecraft.screen)))
        }

        fun unwrap(screen: Screen?): Screen? = if (screen is OptionScreen) screen.originalScreen else screen

        fun newButton(
            component: Component,
            clicked: Button.OnPress,
        ): AbstractWidget = Button.builder(component, clicked).bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT).build()
    }
}
